"""Checks that built/generated assets (frontend bundle, MapLibre font glyphs) are present."""
from pathlib import Path

from geo_lib.logging.console import get_tagged_logger
from website.settings_utils import get_required_setting

_logger = get_tagged_logger('startup')


def check_frontend_files():
    """
    Check if frontend static files have been built.

    Returns:
        bool: True if frontend files exist, False otherwise
    """
    try:
        # Frontend dist directory is relative to BASE_DIR (backend directory)
        frontend_dist = Path(get_required_setting('BASE_DIR')).parent / 'frontend' / 'dist'

        # Check if dist directory exists
        if not frontend_dist.exists():
            _logger.error(f"✗ Frontend dist directory not found: {frontend_dist}")
            _logger.error("  Please build the frontend: cd frontend && npm run build")
            return False

        # Check for index.html (main entry point)
        index_html = frontend_dist / 'index.html'
        if not index_html.exists():
            _logger.error(f"✗ Frontend index.html not found: {index_html}")
            _logger.error("  Please build the frontend: cd frontend && npm run build")
            return False

        # Check for static directory with built assets
        static_dir = frontend_dist / 'static'
        if not static_dir.exists() or not static_dir.is_dir():
            _logger.warning(f"⚠ Frontend static directory not found: {static_dir}")
            _logger.warning("  Frontend may not be fully built")
        else:
            # Check if static directory has any files
            static_files = list(static_dir.iterdir())
            if not static_files:
                _logger.warning(f"⚠ Frontend static directory is empty: {static_dir}")
            else:
                _logger.info(f"✓ Frontend static files found ({len(static_files)} items)")

        _logger.info(f"✓ Frontend files are present: {frontend_dist}")
        return True

    except Exception as e:
        _logger.error(f"✗ Frontend files check failed: {e}")
        return False


def check_font_glyphs():
    """
    Check if MapLibre font glyphs have been generated.

    Returns:
        bool: True if fonts are present, False otherwise
    """
    try:
        # Get assets fonts directory path
        assets_fonts_dir = Path(get_required_setting('BASE_DIR')) / 'assets' / 'fonts'

        # Check if fonts directory exists
        if not assets_fonts_dir.exists():
            _logger.error(f"✗ Fonts directory not found: {assets_fonts_dir}")
            _logger.error("  Please run: cd src/backend && ./generate-map-fonts.sh")
            return False

        if not assets_fonts_dir.is_dir():
            _logger.error(f"✗ Fonts path is not a directory: {assets_fonts_dir}")
            return False

        # Check for font stack directories
        font_stacks = [d for d in assets_fonts_dir.iterdir() if d.is_dir()]
        if not font_stacks:
            _logger.error(f"✗ No font stacks found in: {assets_fonts_dir}")
            _logger.error("  Please run: cd src/backend && ./generate-map-fonts.sh")
            return False

        # Check that at least one font stack has PBF files
        found_pbf_files = False
        common_fonts = ['Noto Sans Regular', 'Open Sans Regular', 'Roboto Regular']
        found_common_fonts = []

        for font_stack in font_stacks:
            # Check for PBF files in this font stack
            pbf_files = list(font_stack.glob('*.pbf'))
            if pbf_files:
                found_pbf_files = True
                if font_stack.name in common_fonts:
                    found_common_fonts.append(font_stack.name)

        if not found_pbf_files:
            _logger.error(f"✗ No PBF font files found in any font stack")
            _logger.error("  Please run: cd src/backend && ./generate-map-fonts.sh")
            return False

        # Check for the first range file (0-255.pbf) in at least one common font
        has_base_range = False
        for font_name in common_fonts:
            font_dir = assets_fonts_dir / font_name
            if font_dir.exists() and (font_dir / '0-255.pbf').exists():
                has_base_range = True
                break

        if not has_base_range:
            _logger.warning(f"⚠ Common font base range (0-255.pbf) not found")
            _logger.warning("  Fonts may be incomplete. Consider re-running: cd src/backend && ./generate-map-fonts.sh")

        _logger.info(f"✓ Font glyphs found: {len(font_stacks)} font stack(s)")
        if found_common_fonts:
            _logger.info(f"  Common fonts available: {', '.join(found_common_fonts)}")
        return True

    except Exception as e:
        _logger.error(f"✗ Font glyphs check failed: {e}")
        return False
