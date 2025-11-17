import os
import traceback
from pathlib import Path

from django import forms
from django.conf import settings
from django.http import HttpResponse, Http404, JsonResponse
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_http_methods

from geo_lib.logging.console import get_access_logger
from geo_lib.processing.icon_manager import store_icon
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


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
def serve_icon(request, icon_hash):
    """
    Serve icon files from storage directory.
    
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
