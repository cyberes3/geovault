"""
Font Management Views

Views for serving MapLibre GL JS font glyphs (PBF files).
"""

from pathlib import Path
from urllib.parse import unquote

from django.conf import settings
from django.http import HttpResponse, Http404
from django.views.decorators.http import require_http_methods

from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger()


@require_http_methods(["GET"])
def serve_font_glyph(request, fontstack, range_str):
    """
    Serve MapLibre font glyph PBF files from assets directory.
    
    URL parameters:
    - fontstack: Font stack name (e.g., 'Noto Sans Regular' or 'Noto%20Sans%20Regular')
                 Can also be a comma-separated list for fallback (e.g., 'Noto Sans Regular,Arial Unicode MS Regular')
    - range_str: Unicode range (e.g., '0-255')
    
    Example URLs:
    - /api/fonts/Noto%20Sans%20Regular/0-255.pbf
    - /api/fonts/Noto%20Sans%20Regular,Arial%20Unicode%20MS%20Regular/0-255.pbf
    """
    # Decode URL-encoded fontstack (handles spaces and special characters)
    fontstack = unquote(fontstack)

    # Security: Prevent directory traversal
    if '..' in fontstack or fontstack.startswith('/'):
        raise Http404("Invalid fontstack path")
    if '..' in range_str or '/' in range_str:
        raise Http404("Invalid range")

    # Validate range format (should be like "0-255" or "0-255.pbf")
    # MapLibre requests with .pbf extension, but we'll handle both cases
    if not range_str.endswith('.pbf'):
        range_str = f"{range_str}.pbf"

    # Validate format: should be "start-end.pbf" where start and end are digits
    range_without_ext = range_str.replace('.pbf', '')
    if '-' not in range_without_ext:
        raise Http404("Invalid range format")

    parts = range_without_ext.split('-')
    if len(parts) != 2 or not all(part.isdigit() for part in parts):
        raise Http404("Invalid range format")

    # Get assets fonts directory path
    assets_fonts_dir = Path(settings.BASE_DIR) / 'assets' / 'fonts'

    # Handle font stacks (comma-separated font names for fallback)
    # MapLibre uses font stacks like "Noto Sans Regular,Arial Unicode MS Regular"
    # We'll try each font in order until we find one that has the requested range
    font_names = [f.strip() for f in fontstack.split(',')]

    file_path = None

    for font_name in font_names:
        # Security: Prevent directory traversal for each font name
        if '..' in font_name or font_name.startswith('/'):
            continue

        # Build the full file path for this font
        candidate_path = (assets_fonts_dir / font_name / range_str).resolve()

        # Security check: ensure the file is within the assets/fonts directory
        try:
            assets_fonts_dir_resolved = assets_fonts_dir.resolve()
            if not str(candidate_path).startswith(str(assets_fonts_dir_resolved)):
                continue
        except (OSError, ValueError):
            continue

        # Check if file exists
        if candidate_path.exists() and candidate_path.is_file():
            file_path = candidate_path
            break

    # If no font in the stack has the file, return 404
    if file_path is None:
        raise Http404(f"Font glyph not found for any font in stack: {fontstack}/{range_str}")

    # Read font file
    font_data = file_path.read_bytes()

    # Create response with appropriate headers
    # PBF files are Protocol Buffer Format, served as application/x-protobuf
    # or application/octet-stream
    response = HttpResponse(font_data, content_type='application/x-protobuf')
    response['Cache-Control'] = 'public, max-age=31536000, immutable'  # Cache for 1 year, immutable
    response['Access-Control-Allow-Origin'] = '*'  # Allow CORS for font requests
    return response
