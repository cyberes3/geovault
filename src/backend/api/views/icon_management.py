import os
import re
import traceback
from io import BytesIO
from pathlib import Path
from urllib.parse import urlparse

from django import forms
from django.conf import settings
from django.http import HttpResponse, Http404, JsonResponse
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_http_methods
from PIL import Image

from geo_lib.logging.console import get_access_logger
from geo_lib.processing.icon_manager import store_icon
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


def _is_allowed_referer(request):
    """
    Check if the request's Referer header is from an allowed domain.
    
    Allows:
    - All requests if hot-linking is enabled in config
    - Requests with no Referer header AND same-site origin (direct access, bookmarks)
    - Requests where Referer matches the current request's host (same domain)
    
    Blocks:
    - Requests with Referer from a different domain (hot-linking attempt) if hot-linking is disabled
    - Cross-site requests (detected via Sec-Fetch-Site header) even without Referer
    
    Args:
        request: Django request object
        
    Returns:
        True if referer is allowed, False if hot-linking is detected
    """
    # If hot-linking is allowed in config, allow all requests
    if settings.ICON_ALLOW_HOTLINKING:
        return True
    
    # Check Sec-Fetch-Site header (modern browsers send this for cross-site requests)
    # This helps detect hot-linking even when Referer header is missing (e.g., file:// protocol)
    sec_fetch_site = request.META.get('HTTP_SEC_FETCH_SITE', '').lower()
    if sec_fetch_site == 'cross-site':
        # This is a cross-site request - block it as hot-linking
        logger.debug(f"Hot-linking attempt blocked: Sec-Fetch-Site=cross-site, current_host={request.get_host()}")
        return False
    
    referer = request.META.get('HTTP_REFERER', '')
    
    # Allow requests with no referer if they're same-site (not cross-site)
    # Same-site includes: same-origin, same-site, or none (for direct navigation)
    if not referer:
        # If Sec-Fetch-Site indicates same-site or none, allow it
        if sec_fetch_site in ('same-origin', 'same-site', 'none', ''):
            return True
        # Otherwise, be cautious and block
        logger.debug(f"Hot-linking attempt blocked: no referer and Sec-Fetch-Site={sec_fetch_site}, current_host={request.get_host()}")
        return False
    
    try:
        # Parse the referer URL
        referer_parsed = urlparse(referer)
        referer_host = referer_parsed.netloc.lower()
        
        # Get the current request's host
        current_host = request.get_host().lower()
        
        # Remove port numbers for comparison (if present)
        # request.get_host() may include port, so we need to handle both cases
        if ':' in referer_host:
            referer_host = referer_host.split(':')[0]
        if ':' in current_host:
            current_host = current_host.split(':')[0]
        
        # Allow if referer host matches current host
        if referer_host == current_host:
            return True
        
        # Block if referer is from a different domain
        logger.debug(f"Hot-linking attempt blocked: referer={referer}, current_host={current_host}")
        return False
        
    except Exception as e:
        # If there's any error parsing, be permissive (allow the request)
        # This prevents blocking legitimate requests due to parsing errors
        logger.debug(f"Error checking referer: {str(e)}")
        return True


class IconUploadForm(forms.Form):
    """Form for icon file upload"""
    file = forms.FileField()


@login_required_401
@csrf_protect
@require_http_methods(["POST"])
def upload_icon(request):
    """
    API endpoint to upload a custom icon file.
    
    Request: POST with multipart/form-data containing 'file' field
    Returns: JSON with success status and icon URL path
    """
    try:
        if not request.FILES:
            return JsonResponse({
                'success': False,
                'error': 'No file provided',
                'code': 400
            }, status=400)

        form = IconUploadForm(request.POST, request.FILES)
        if not form.is_valid():
            return JsonResponse({
                'success': False,
                'error': 'Invalid form data',
                'code': 400
            }, status=400)

        uploaded_file = request.FILES['file']
        file_name = uploaded_file.name

        # Validate file extension (only PNG, JPG, ICO allowed for uploads)
        file_ext = os.path.splitext(file_name)[1].lower()
        if file_ext not in settings.ICON_UPLOAD_ALLOWED_EXTENSIONS:
            return JsonResponse({
                'success': False,
                'error': f'Invalid file extension. Allowed extensions: {", ".join(sorted(settings.ICON_UPLOAD_ALLOWED_EXTENSIONS))}',
                'code': 400
            }, status=400)

        # Read file data
        try:
            icon_data = uploaded_file.read()
        except Exception as e:
            logger.error(f"Error reading uploaded icon file: {str(e)}")
            return JsonResponse({
                'success': False,
                'error': 'Failed to read file',
                'code': 500
            }, status=500)

        # Validate file size (500KB limit for uploads)
        if len(icon_data) > settings.ICON_UPLOAD_MAX_SIZE_BYTES:
            max_size_mb = settings.ICON_UPLOAD_MAX_SIZE_BYTES / 1024
            return JsonResponse({
                'success': False,
                'error': f'File size exceeds maximum allowed size of {max_size_mb:.0f}KB',
                'code': 400
            }, status=400)

        # Store icon using existing icon manager
        icon_url = store_icon(icon_data, file_name)

        if not icon_url:
            return JsonResponse({
                'success': False,
                'error': 'Failed to store icon',
                'code': 500
            }, status=500)

        return JsonResponse({
            'success': True,
            'icon_url': icon_url,
            'code': 200
        }, status=200)

    except Exception as e:
        logger.error(f"Error uploading icon: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': f'Internal server error: {str(e)}',
            'code': 500
        }, status=500)


@require_http_methods(["GET"])
def serve_user_icon(request, icon_hash):
    """
    Serve uploaded icon files from storage directory.
    
    URL parameter:
    - icon_hash: Hash of the icon file (with extension, e.g., 'abc123def456.png')
    """
    try:
        # Validate icon_hash format (should be hash + extension)
        if not icon_hash or len(icon_hash) < 5:  # At least hash (64 chars) + extension (e.g., .png)
            raise Http404("Invalid icon hash")

        # Extract hash and extension
        # Icon hash format: {hash}{extension} (e.g., abc123def456.png)
        # Hash is 64 characters (SHA-256), extension starts after that
        # Find the last dot to separate hash from extension
        if '.' not in icon_hash:
            raise Http404("Invalid icon hash format - missing extension")

        # Split on last dot to get hash and extension
        hash_part, extension = icon_hash.rsplit('.', 1)
        extension = '.' + extension  # Add leading dot back

        # Validate hash length (should be 64 characters for SHA-256)
        if len(hash_part) != 64:
            raise Http404("Invalid icon hash format - hash length incorrect")

        # Validate extension
        valid_extensions = {'.png', '.jpg', '.jpeg', '.gif', '.bmp', '.svg', '.webp', '.ico'}
        if extension not in valid_extensions:
            raise Http404("Invalid icon extension")

        # Check referer to prevent hot-linking
        if not _is_allowed_referer(request):
            return HttpResponse("Hot-linking not allowed", status=403)

        # Get storage path
        storage_dir = Path(settings.ICON_STORAGE_DIR)
        icon_path = storage_dir / hash_part[0:2] / hash_part[2:4] / icon_hash

        # Check if file exists
        if not icon_path.exists() or not icon_path.is_file():
            raise Http404("Icon not found")

        # Read icon file
        icon_data = icon_path.read_bytes()

        # Determine content type based on extension
        content_types = {
            '.png': 'image/png',
            '.jpg': 'image/jpeg',
            '.jpeg': 'image/jpeg',
            '.gif': 'image/gif',
            '.bmp': 'image/bmp',
            '.svg': 'image/svg+xml',
            '.webp': 'image/webp',
            '.ico': 'image/x-icon',
        }
        content_type = content_types.get(extension, 'image/png')

        # Create response with appropriate headers
        response = HttpResponse(icon_data, content_type=content_type)
        response['Cache-Control'] = 'public, max-age=31536000'  # Cache for 1 year
        return response

    except Http404:
        raise
    except Exception as e:
        logger.error(f"Error serving icon {icon_hash}: {traceback.format_exc()}")
        raise Http404("Icon not found")


@require_http_methods(["GET"])
def serve_system_icon(request, path):
    """
    Serve built-in icon files from assets directory.
    
    URL parameter:
    - path: Relative path within assets/icons/ (e.g., 'caltopo/tidepool.png')
    """
    try:
        # Security: Prevent directory traversal
        if '..' in path or path.startswith('/'):
            raise Http404("Invalid icon path")
        
        # Get assets icons directory path
        assets_icons_dir = Path(settings.BASE_DIR) / 'assets' / 'icons'
        
        # Build the full file path
        file_path = (assets_icons_dir / path).resolve()
        
        # Security check: ensure the file is within the assets/icons directory
        try:
            assets_icons_dir_resolved = assets_icons_dir.resolve()
            if not str(file_path).startswith(str(assets_icons_dir_resolved)):
                raise Http404("Invalid icon path")
        except (OSError, ValueError):
            raise Http404("Invalid icon path")
        
        # Check referer to prevent hot-linking
        if not _is_allowed_referer(request):
            return HttpResponse("Hot-linking not allowed", status=403)
        
        # Check if file exists
        if not file_path.exists() or not file_path.is_file():
            raise Http404("Icon not found")
        
        # Read icon file
        icon_data = file_path.read_bytes()
        
        # Determine content type based on extension
        content_types = {
            '.png': 'image/png',
            '.jpg': 'image/jpeg',
            '.jpeg': 'image/jpeg',
            '.gif': 'image/gif',
            '.bmp': 'image/bmp',
            '.svg': 'image/svg+xml',
            '.webp': 'image/webp',
            '.ico': 'image/x-icon',
        }
        suffix = file_path.suffix.lower()
        content_type = content_types.get(suffix, 'image/png')
        
        # Create response with appropriate headers
        response = HttpResponse(icon_data, content_type=content_type)
        response['Cache-Control'] = 'public, max-age=31536000'  # Cache for 1 year
        return response
        
    except Http404:
        raise
    except Exception as e:
        logger.error(f"Error serving system icon {path}: {traceback.format_exc()}")
        raise Http404("Icon not found")


@require_http_methods(["GET"])
def recolor_icon(request):
    """
    Recolor a built-in icon by replacing dark pixels with the specified color.
    
    Query parameters:
    - icon: Icon path relative to assets/icons/ (e.g., 'caltopo/4wd.png')
    - color: Hex color string (e.g., '#00ff30')
    
    Returns: PNG image with recolored pixels
    """
    try:
        # Get query parameters
        icon_path_param = request.GET.get('icon', '').strip()
        color = request.GET.get('color', '').strip()
        
        # Validate icon path
        if not icon_path_param:
            return JsonResponse({
                'success': False,
                'error': 'Missing required parameter: icon',
                'code': 400
            }, status=400)
        
        # Validate color format (hex color: #RRGGBB)
        if not color or not re.match(r'^#[0-9A-Fa-f]{6}$', color):
            return JsonResponse({
                'success': False,
                'error': 'Invalid color format. Must be hex color (e.g., #00ff30)',
                'code': 400
            }, status=400)
        
        # Security: Prevent directory traversal
        if '..' in icon_path_param or icon_path_param.startswith('/'):
            return JsonResponse({
                'success': False,
                'error': 'Invalid icon path',
                'code': 400
            }, status=400)
        
        # Get icon path from assets directory
        assets_icons_dir = Path(settings.BASE_DIR) / 'assets' / 'icons'
        icon_path = (assets_icons_dir / icon_path_param).resolve()
        
        # Validate path is within assets/icons directory (prevent directory traversal)
        try:
            assets_icons_dir_resolved = assets_icons_dir.resolve()
            if not str(icon_path).startswith(str(assets_icons_dir_resolved)):
                return JsonResponse({
                    'success': False,
                    'error': 'Invalid icon path',
                    'code': 400
                }, status=400)
        except (OSError, ValueError):
            return JsonResponse({
                'success': False,
                'error': 'Invalid icon path',
                'code': 400
            }, status=400)
        
        # Check referer to prevent hot-linking
        if not _is_allowed_referer(request):
            return HttpResponse("Hot-linking not allowed", status=403)
        
        # Check if icon exists
        if not icon_path.exists() or not icon_path.is_file():
            raise Http404(f"Icon not found: {icon_path_param}")
        
        # Load image using PIL
        try:
            img = Image.open(icon_path)
            # Convert to RGBA if not already (ensures we have alpha channel)
            if img.mode != 'RGBA':
                img = img.convert('RGBA')
        except Exception as e:
            logger.error(f"Error loading icon {icon_path_param}: {str(e)}")
            return JsonResponse({
                'success': False,
                'error': f'Failed to load icon: {str(e)}',
                'code': 500
            }, status=500)
        
        # Parse color
        hex_color = color.replace('#', '')
        r = int(hex_color[0:2], 16)
        g = int(hex_color[2:4], 16)
        b = int(hex_color[4:6], 16)
        
        # Get image data
        pixels = img.load()
        width, height = img.size
        
        # Threshold for converting to pure black/white (brightness < 200)
        brightness_threshold = 200
        
        # Recolor dark pixels
        pixels_recolored = 0
        total_pixels = 0
        
        for y in range(height):
            for x in range(width):
                pixel = pixels[x, y]
                pixel_r, pixel_g, pixel_b, pixel_a = pixel
                
                # Only process non-transparent pixels
                if pixel_a > 0:
                    total_pixels += 1
                    # Calculate brightness using relative luminance
                    brightness = 0.299 * pixel_r + 0.587 * pixel_g + 0.114 * pixel_b
                    
                    # If pixel is dark enough, replace with target color
                    if brightness < brightness_threshold:
                        pixels[x, y] = (r, g, b, pixel_a)  # Keep original alpha
                        pixels_recolored += 1
        
        # Convert image to PNG bytes
        output = BytesIO()
        img.save(output, format='PNG')
        output.seek(0)
        image_data = output.read()
        
        # Create response
        response = HttpResponse(image_data, content_type='image/png')
        response['Cache-Control'] = 'public, max-age=3600'  # Cache for 1 hour
        return response
        
    except Http404:
        raise
    except Exception as e:
        logger.error(f"Error recoloring icon: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': f'Internal server error: {str(e)}',
            'code': 500
        }, status=500)


@require_http_methods(["GET"])
def serve_icon_registry(request):
    """
    Serve the icon registry JSON file.
    
    Returns: JSON file containing icon registry with all available system icons
    """
    try:
        # Get path to icon registry file
        registry_path = Path(settings.BASE_DIR) / 'assets' / 'icons' / 'icon-registry.json'
        
        # Check if file exists
        if not registry_path.exists() or not registry_path.is_file():
            raise Http404("Icon registry not found")
        
        # Read JSON file
        registry_data = registry_path.read_text(encoding='utf-8')
        
        # Create response with JSON content type
        response = HttpResponse(registry_data, content_type='application/json')
        response['Cache-Control'] = 'public, max-age=3600'  # Cache for 1 hour
        return response
        
    except Http404:
        raise
    except Exception as e:
        logger.error(f"Error serving icon registry: {traceback.format_exc()}")
        raise Http404("Icon registry not found")
