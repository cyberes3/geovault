import os
import re
import traceback
from io import BytesIO
from pathlib import Path

from PIL import Image, UnidentifiedImageError
from django import forms
from django.http import HttpResponse, Http404
from django.views.decorators.http import require_http_methods

from api.services.icon_recolor_service import recolor_icon_file_to_png_bytes
from api.utils.responses import error_response, success_response
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.icons.get import ICON_CONTENT_TYPES, parse_user_icon_hash
from geo_lib.processing.icons.icon_manager import store_icon
from geo_lib.processing.logging import ImportLog
from geo_lib.security.rate_limit import RedisRateLimiter
from geo_lib.utils.secure_path import is_path_under_base, secure_filename, secure_path
from geo_lib.website.auth import api_or_login_required_401
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()

_upload_icon_rate_limiter = RedisRateLimiter(name='upload_icon', limit=30, window_seconds=60.0)


def _icon_response(icon_data: bytes, content_type: str) -> HttpResponse:
    """Build an icon HttpResponse with nosniff set, since these routes ultimately serve
    bytes that originated from user uploads or third-party import sources."""
    response = HttpResponse(icon_data, content_type=content_type)
    response['X-Content-Type-Options'] = 'nosniff'
    return response


class IconUploadForm(forms.Form):
    """Form for icon file upload"""
    file = forms.FileField()


@api_or_login_required_401()
@_upload_icon_rate_limiter()
@require_http_methods(["POST"])
def upload_icon(request):
    """
    API endpoint to upload a custom icon file.
    
    Request: POST with multipart/form-data containing 'file' field
    Returns: JSON with success status and icon URL path
    """
    if not request.FILES:
        return error_response('No file provided', code=400)

    form = IconUploadForm(request.POST, request.FILES)
    if not form.is_valid():
        return error_response('Invalid form data', code=400)

    uploaded_file = request.FILES['file']
    file_name = secure_filename(uploaded_file.name)
    if not file_name:
        file_name = "upload.png"

    # Validate file extension (PNG, JPG, WEBP allowed for uploads; WEBP is converted to
    # PNG below since we standardize stored icons on PNG/JPG)
    file_ext = os.path.splitext(file_name)[1].lower()
    allowed_extensions = get_required_setting('ICON_UPLOAD_ALLOWED_EXTENSIONS')
    if file_ext not in allowed_extensions:
        return error_response(
            f'Invalid file extension. Allowed extensions: {", ".join(sorted(allowed_extensions))}',
            code=400
        )

    # Read file data
    try:
        icon_data = uploaded_file.read()
    except Exception:
        _logger.error(f"Error reading uploaded icon file: {traceback.format_exc()}")
        return error_response('Failed to read file', code=500)

    # Validate file size (500KB limit for uploads)
    max_upload_bytes = get_required_setting('ICON_UPLOAD_MAX_SIZE_BYTES')
    if len(icon_data) > max_upload_bytes:
        max_size_mb = max_upload_bytes / 1024
        return error_response(f'File size exceeds maximum allowed size of {max_size_mb:.0f}KB', code=400)

    # WEBP is accepted as an upload input but converted to PNG here so every stored
    # user icon is PNG or JPG, regardless of what the browser originally sent.
    if file_ext == '.webp':
        try:
            with Image.open(BytesIO(icon_data)) as img:
                img.load()
                output = BytesIO()
                img.convert('RGBA').save(output, format='PNG')
                icon_data = output.getvalue()
        except (UnidentifiedImageError, OSError, ValueError):
            return error_response('Invalid WEBP image', code=400)
        file_name = os.path.splitext(file_name)[0] + '.png'

    # Store icon using existing icon manager
    # Create empty ImportLog for non-import use case
    import_log = ImportLog()
    icon_url = store_icon(icon_data, file_name, import_log, stats={'successful': 0, 'failed': 0})

    if not icon_url:
        return error_response('Failed to store icon', code=500)

    return success_response({'icon_url': icon_url})


@require_http_methods(["GET"])
def serve_user_icon(request, icon_hash):
    """
    Serve uploaded icon files from storage directory.
    
    URL parameter:
    - icon_hash: Hash of the icon file (with extension, e.g., 'abc123def456.png')
    """
    parsed = parse_user_icon_hash(icon_hash)
    if not parsed:
        raise Http404("Invalid icon hash")
    hash_part, extension = parsed

    storage_dir = Path(get_required_setting('ICON_STORAGE_DIR'))
    icon_path = storage_dir / hash_part[0:2] / hash_part[2:4] / icon_hash
    resolved = icon_path.resolve()

    if not is_path_under_base(resolved, storage_dir):
        raise Http404("Invalid icon path")

    if not resolved.exists() or not resolved.is_file():
        raise Http404("Icon not found")

    icon_data = resolved.read_bytes()

    # Determine content type based on extension
    content_type = ICON_CONTENT_TYPES.get(extension, 'image/png')

    # Create response with appropriate headers
    response = _icon_response(icon_data, content_type)
    response['Cache-Control'] = 'public, max-age=31536000, immutable'  # Cache for 1 year, immutable
    return response


@require_http_methods(["GET"])
def serve_system_icon(request, path):
    """
    Serve built-in icon files from assets directory.
    
    URL parameter:
    - path: Relative path within assets/icons/ (e.g., 'caltopo/tidepool.png')
    """
    # Security: Prevent directory traversal
    if '..' in path or path.startswith('/'):
        raise Http404("Invalid icon path")

    path = secure_path(path)

    # Get assets icons directory path
    assets_icons_dir = Path(get_required_setting('BASE_DIR')) / 'assets' / 'icons'

    file_path = (assets_icons_dir / path).resolve()

    if not is_path_under_base(file_path, assets_icons_dir):
        raise Http404("Invalid icon path")

    # Check if file exists
    if not file_path.exists() or not file_path.is_file():
        raise Http404("Icon not found")

    # Read icon file
    icon_data = file_path.read_bytes()

    # Determine content type based on extension
    suffix = file_path.suffix.lower()
    content_type = ICON_CONTENT_TYPES.get(suffix, 'image/png')

    # Create response with appropriate headers
    response = _icon_response(icon_data, content_type)
    response['Cache-Control'] = 'public, max-age=31536000, immutable'  # Cache for 1 year, immutable
    return response


@require_http_methods(["GET"])
def recolor_icon(request):
    """
    Recolor a built-in icon by replacing dark pixels with the specified color.
    
    Query parameters:
    - icon: Icon path relative to assets/icons/ (e.g., 'caltopo/4wd.png')
    - color: Hex color string (e.g., '#00ff30')
    
    Returns: PNG image with recolored pixels
    """
    # Get query parameters
    icon_path_param = request.GET.get('icon', '').strip()
    color = request.GET.get('color', '').strip()

    # Validate icon path
    if not icon_path_param:
        return error_response('Missing required parameter: icon', code=400)

    # Validate color format (hex color: #RRGGBB)
    if not color or not re.match(r'^#[0-9A-Fa-f]{6}$', color):
        return error_response('Invalid color format. Must be hex color (e.g., #00ff30)', code=400)

    # Security: Prevent directory traversal
    if '..' in icon_path_param or icon_path_param.startswith('/'):
        return error_response('Invalid icon path', code=400)

    icon_path_param = secure_path(icon_path_param)

    assets_icons_dir = Path(get_required_setting('BASE_DIR')) / 'assets' / 'icons'
    icon_path = (assets_icons_dir / icon_path_param).resolve()

    if not is_path_under_base(icon_path, assets_icons_dir):
        return error_response('Invalid icon path', code=400)

    # Check if icon exists
    if not icon_path.exists() or not icon_path.is_file():
        raise Http404("Icon not found")

    try:
        image_data = recolor_icon_file_to_png_bytes(icon_path, color)
    except (UnidentifiedImageError, OSError, ValueError):
        _logger.error(f"Error loading icon {icon_path_param}: {traceback.format_exc()}")
        return error_response('Failed to load icon', code=500)

    # Create response
    response = _icon_response(image_data, 'image/png')
    response['Cache-Control'] = 'public, max-age=3600'  # Cache for 1 hour
    return response


@require_http_methods(["GET"])
def serve_icon_registry(request):
    """
    Serve the icon registry JSON file.
    
    Returns: JSON file containing icon registry with all available system icons
    """
    # Get path to icon registry file
    registry_path = Path(get_required_setting('BASE_DIR')) / 'assets' / 'icons' / 'icon-registry.json'

    # Check if file exists
    if not registry_path.exists() or not registry_path.is_file():
        raise Http404("Icon registry not found")

    # Read JSON file
    registry_data = registry_path.read_text(encoding='utf-8')

    # Create response with JSON content type
    response = HttpResponse(registry_data, content_type='application/json')
    response['Cache-Control'] = 'public, max-age=3600'  # Cache for 1 hour
    return response
