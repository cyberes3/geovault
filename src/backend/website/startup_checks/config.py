"""
Configuration validation checks: config file presence, Site framework, social-preview tile
source, file-type size limits, and warning-only checks (MaxMind, email).
"""
from pathlib import Path

from django.conf import settings
from django.contrib.sites.models import Site

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.file_types import FILE_TYPE_CONFIGS
from geo_lib.tile_sources.registry import get_tile_source
from website.config.loader import get_config_path
from website.settings_utils import get_required_setting

_logger = get_tagged_logger('startup')


def check_social_preview_tilesource():
    """
    Validate tilesources.social_preview_raster_source points to a registered raster (xyz) source.

    Uses the internal tile registry (get_tile_source), not only client-facing URLs, so
    tilesources.proxy_osm rewriting the client URL to /api/tiles/... does not invalidate
    the check when url_template remains a direct raster template.

    Returns:
        bool: True when valid, False otherwise.
    """
    try:
        source_id = get_required_setting('TILESOURCES_SOCIAL_PREVIEW_RASTER_SOURCE').strip() or "osm"
        cfg = get_tile_source(source_id)

        if not cfg:
            _logger.error(
                "✗ Social preview tile source '%s' is not registered. "
                "Set tilesources.social_preview_raster_source to a valid raster basemap source id (default: osm).",
                source_id,
            )
            return False

        if cfg.get("type") != "xyz":
            _logger.error(
                "✗ Social preview tile source '%s' is not a raster xyz layer (type=%r). "
                "Please configure a raster basemap (example: osm).",
                source_id,
                cfg.get("type"),
            )
            return False

        client_config = cfg.get("client_config", {})
        source_type = client_config.get("type")
        tile_url = client_config.get("url")
        url_template = cfg.get("url_template")
        has_client_template = (
            isinstance(tile_url, str)
            and "{z}" in tile_url
            and "{x}" in tile_url
            and "{y}" in tile_url
        )
        has_upstream_template = (
            isinstance(url_template, str)
            and "{z}" in url_template
            and "{x}" in url_template
            and "{y}" in url_template
        )
        if source_type != "xyz" or (not has_client_template and not has_upstream_template):
            _logger.error(
                "✗ Social preview tile source '%s' is not a raster xyz layer with a tile URL template. "
                "Found client_config.type=%r. Please configure a raster basemap (example: osm).",
                source_id,
                source_type,
            )
            return False

        _logger.info("✓ Social preview tile source is valid: %s", source_id)
        return True
    except Exception as e:
        _logger.error(f"✗ Social preview tile source check failed: {e}")
        return False


def check_file_type_max_size():
    """
    Check that all FILE_TYPE_CONFIGS max_size values are less than 200MB.
    This is critical because values >= 200MB will break the database.

    Returns:
        bool: True if all max_size values are valid, False otherwise
    """
    try:
        MAX_ALLOWED_SIZE_BYTES = 200 * 1024 * 1024  # 200MB in bytes
        invalid_configs = []

        for file_type, config in FILE_TYPE_CONFIGS.items():
            if config.max_size >= MAX_ALLOWED_SIZE_BYTES:
                max_size_mb = config.max_size / (1024 * 1024)
                invalid_configs.append((file_type.value, config.max_size, max_size_mb))

        if invalid_configs:
            _logger.error("✗ File type max_size validation failed!")
            _logger.error(f"  The following file types have max_size >= 200MB (which will break the database):")
            for file_type_name, max_size_bytes, max_size_mb in invalid_configs:
                _logger.error(f"    - {file_type_name}: max_size = {max_size_bytes} bytes ({max_size_mb:.1f}MB)")
            _logger.error(f"  All max_size values must be less than 200MB ({MAX_ALLOWED_SIZE_BYTES} bytes)")
            _logger.error("  Please fix FILE_TYPE_CONFIGS in geo_lib/processing/file_types.py")
            return False

        _logger.info("✓ File type max_size validation passed (all values < 200MB)")
        return True

    except Exception as e:
        _logger.error(f"✗ File type max_size check failed: {e}")
        return False


def check_config_file():
    """
    Check if configuration file exists.
    This is a warning-only check.
    """
    try:
        config_path = get_config_path()

        if not config_path.exists():
            _logger.warning(f"⚠ Configuration file not found: {config_path}")
            _logger.warning("  Using default configuration values. Create config.yaml for custom settings.")
        else:
            _logger.info(f"✓ Configuration file found: {config_path}")

    except Exception as e:
        _logger.warning(f"⚠ Could not check configuration file: {e}")


def check_maxmind_database():
    """
    Check if MaxMind database file exists.
    This is a warning-only check (optional feature).
    """
    try:
        maxmind_path = get_required_setting('MAXMIND_DATABASE_PATH')
        if maxmind_path:
            maxmind_file = Path(maxmind_path)
            if not maxmind_file.exists():
                _logger.warning(f"⚠ MaxMind database file not found: {maxmind_path}")
                _logger.warning("  IP geolocation features may not work. This is optional.")
            else:
                _logger.info(f"✓ MaxMind database found: {maxmind_path}")
        else:
            _logger.info("  MaxMind database path not configured (optional)")

    except Exception as e:
        _logger.warning(f"⚠ Could not check MaxMind database: {e}")


def check_email_config():
    """
    Check if email configuration is using default/unconfigured values.
    This is a warning-only check (optional feature).
    """
    try:
        email_host = get_required_setting('EMAIL_HOST')
        email_user = get_required_setting('EMAIL_HOST_USER')
        email_password = get_required_setting('EMAIL_HOST_PASSWORD')
        from_email = get_required_setting('DEFAULT_FROM_EMAIL')

        # Check for default/unconfigured values
        is_default = False
        issues = []

        if email_host == 'smtp.gmail.com' and not email_user:
            is_default = True
            issues.append("using default SMTP host without username")

        if not email_user:
            is_default = True
            issues.append("EMAIL_HOST_USER is not set")

        if not email_password:
            is_default = True
            issues.append("EMAIL_HOST_PASSWORD is not set")

        if from_email == 'noreply@example.com':
            is_default = True
            issues.append("using default from_email (noreply@example.com)")

        if is_default:
            _logger.warning("⚠ Email configuration appears to be using default/unconfigured values:")
            for issue in issues:
                _logger.warning(f"  - {issue}")
            _logger.warning("  Email features (password reset, notifications) may not work.")
        else:
            _logger.info("✓ Email configuration appears to be configured")

    except Exception as e:
        _logger.warning(f"⚠ Could not check email configuration: {e}")


def check_site_configuration():
    """
    Check if Django Sites framework is properly configured.
    Verifies that Site object can be created/updated from settings.
    This is a critical check as it's required for email confirmation URLs.
    Requires that site.domain is explicitly set (not using the default value).

    Returns:
        bool: True if Site configuration is valid, False otherwise
    """
    try:
        # Check if required settings exist
        if not hasattr(settings, 'SITE_DOMAIN') or not hasattr(settings, 'SITE_NAME'):
            _logger.error("✗ Site configuration missing: SITE_DOMAIN and SITE_NAME must be defined in settings")
            _logger.error("  Email confirmation links will not work without proper Site configuration")
            return False

        if not hasattr(settings, 'SITE_ID'):
            _logger.error("✗ Site configuration missing: SITE_ID must be defined in settings")
            return False

        # Check if site.domain is explicitly set (not using default)
        default_domain = 'geovault.example.com'

        # SITE_DOMAIN always has a value (defaults to default_domain when unset in config.yaml)
        site_domain_config = settings.SITE_DOMAIN
        if not site_domain_config or site_domain_config == default_domain:
            _logger.error("✗ Site domain is not configured: 'site.domain' must be explicitly set in config.yaml")
            _logger.error(f"  Current value: {site_domain_config if site_domain_config else 'not set (using default: ' + default_domain + ')'}")
            _logger.error("  Please set 'site.domain' in your config.yaml file to your actual domain name")
            _logger.error("  Example: site.domain: mydomain.com")
            return False

        site_domain = get_required_setting('SITE_DOMAIN')
        site_name = get_required_setting('SITE_NAME')
        site_id = get_required_setting('SITE_ID')

        # Verify we can create/update the Site object
        # This is the same logic used in NoUsernameAccountAdapter.get_email_confirmation_url()
        try:
            site, created = Site.objects.get_or_create(id=site_id)

            if created:
                _logger.info(f"✓ Created Site object with ID {site_id}")

            # Update if needed
            if site.domain != site_domain or site.name != site_name:
                site.domain = site_domain
                site.name = site_name
                site.save()
                _logger.info(f"✓ Updated Site object: {site_name} ({site_domain})")
            else:
                _logger.info(f"✓ Site configuration is valid: {site_name} ({site_domain})")

            # Verify we can retrieve it
            Site.objects.get(id=site_id)

            return True

        except Exception as e:
            _logger.error(f"✗ Failed to create/update Site object: {e}")
            return False

    except Exception as e:
        _logger.error(f"✗ Site configuration check failed: {e}")
        return False
