import traceback
from concurrent.futures import ThreadPoolExecutor, as_completed, TimeoutError as FutureTimeoutError

import requests
import urllib3
from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.db_connection import ensure_db_connection_cleanup
from website.auth_decorators import api_or_login_required_401
from website.settings_utils import get_required_setting, get_setting
from website.startup_checks.celery import check_celery_beat, check_celery_worker
from website.startup_checks.environment import (
    check_database_connection,
    check_postgis_installation,
    check_redis_connection,
)

_logger = get_tagged_logger()

# Maximum timeout for health checks (in seconds)
# Health checks should be fast - external APIs get shorter timeouts
HEALTH_CHECK_OVERALL_TIMEOUT = 10
HEALTH_CHECK_EXTERNAL_API_TIMEOUT = 5


@api_or_login_required_401()
@require_http_methods(["GET"])
def health_check(request):
    """
    Health check endpoint that verifies critical system components.

    All checks run in parallel using threads for better performance.

    Returns:
        JsonResponse with status "healthy" (200) or "unhealthy" (500) and components status
    """
    components = {}
    overall_healthy = True

    try:
        # Build list of checks to run in parallel
        # Always run critical health checks (suppress verbose logging)
        checks_to_run = [
            ("database", lambda: check_database_connection(suppress_logging=True)),
            ("redis", lambda: check_redis_connection(suppress_logging=True)),
            ("postgis", lambda: check_postgis_installation(suppress_logging=True)),
            ("celery_worker", lambda: check_celery_worker(suppress_logging=True)),
            ("celery_beat", lambda: check_celery_beat(suppress_logging=True, wait_for_heartbeat=False)),
        ]

        # Check areas server only if reverse geocoding is enabled
        reverse_geocoding_enabled = settings.REVERSE_GEOCODING_ENABLED
        if reverse_geocoding_enabled:
            base_url = (get_setting("AREAS_SERVER_URL") or "").strip()
            if base_url:
                checks_to_run.append(("areas_server", check_areas_server))
            else:
                components["areas_server"] = "not_configured"
        else:
            components["areas_server"] = "disabled"

        # Always check Elevation API (it will return True if disabled)
        elevation_enabled = get_required_setting('ELEVATION_API_ENABLED')
        if not elevation_enabled:
            components["elevation_api"] = "disabled"
        else:
            checks_to_run.append(("elevation_api", check_elevation_api))

        # Check forward reverse_geocoding API based on geocoding_search_mode
        geocoding_mode = settings.GEOCODING_SEARCH_MODE
        if geocoding_mode is None:
            components["forward_geocoding_api"] = "not_configured"
        elif geocoding_mode == "maptiler":
            if settings.MAPTILER_API_KEY:
                checks_to_run.append(("maptiler_geocoding_api", check_maptiler_geocoding_api))
            else:
                components["maptiler_geocoding_api"] = "not_configured"
        elif geocoding_mode == "google":
            if settings.GOOGLE_GEOCODING_API_KEY:
                checks_to_run.append(("google_geocoding_api", check_google_geocoding_api))
            else:
                components["google_geocoding_api"] = "not_configured"

        # Run all checks in parallel using ThreadPoolExecutor with overall timeout
        with ThreadPoolExecutor(max_workers=len(checks_to_run)) as executor:
            # Submit all checks
            future_to_check = {
                executor.submit(_run_check_safely, name, check_func): name
                for name, check_func in checks_to_run
            }

            # Collect results as they complete, with overall timeout
            try:
                for future in as_completed(future_to_check, timeout=HEALTH_CHECK_OVERALL_TIMEOUT):
                    name, status, is_healthy = future.result()
                    components[name] = status
                    if not is_healthy:
                        overall_healthy = False
            except FutureTimeoutError:
                # If overall timeout is reached, mark any incomplete checks as timeout
                for future, name in future_to_check.items():
                    if not future.done():
                        components[name] = "timeout"
                        overall_healthy = False

        status = "healthy" if overall_healthy else "unhealthy"
        status_code = 200 if overall_healthy else 500

        return JsonResponse({
            "status": status,
            "components": dict(sorted(components.items())),
        }, status=status_code)

    except Exception:
        # Any exception means unhealthy
        _logger.error(f"Health check failed with exception:\n{traceback.format_exc()}")
        return JsonResponse({
            "status": "unhealthy",
            "components": dict(sorted(components.items())),
        }, status=500)


def check_areas_server() -> bool:
    """
    Check areas server health by GETting its /health endpoint.

    Returns:
        True if the server returns 200 and status "ok", False otherwise.
    """
    try:
        base_url = (get_setting("AREAS_SERVER_URL") or "").strip()
        if not base_url:
            return False
        url = base_url.rstrip("/") + "/health"
        verify_ssl = get_setting("AREAS_SERVER_VERIFY_SSL", True)
        if not verify_ssl:
            urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
        response = requests.get(
            url,
            timeout=HEALTH_CHECK_EXTERNAL_API_TIMEOUT,
            verify=verify_ssl,
        )
        if response.status_code != 200:
            return False
        data = response.json()
        return data.get("status") == "ok"
    except Exception as e:
        if not isinstance(e, (requests.exceptions.RequestException, requests.exceptions.Timeout)):
            _logger.warning(
                f"Areas server health check failed with unexpected exception:\n{traceback.format_exc()}"
            )
        return False


def check_elevation_api() -> bool:
    """
    Check Elevation API health by making a simple request.
    Uses a shorter timeout for health checks to prevent hanging.
    
    Returns:
        True if API is healthy, False otherwise
    """
    try:
        if not get_required_setting('ELEVATION_API_ENABLED'):
            # If disabled, consider it healthy (not a failure)
            return True
        api_url = get_required_setting('ELEVATION_API_URL')

        # Use shorter timeout for health checks (not the full configured timeout)
        response = requests.post(
            api_url,
            json=[[0.0, 0.0]],
            headers={'Content-Type': 'application/json'},
            timeout=HEALTH_CHECK_EXTERNAL_API_TIMEOUT
        )

        if response.status_code == 200:
            data = response.json()
            return isinstance(data, list) and len(data) > 0

        return False
    except Exception as e:
        # Log unexpected exceptions (network errors are expected, but bugs are not)
        if not isinstance(e, (requests.exceptions.RequestException, requests.exceptions.Timeout)):
            _logger.warning(f"Elevation API health check failed with unexpected exception:\n{traceback.format_exc()}")
        return False


def check_maptiler_geocoding_api() -> bool:
    """
    Check MapTiler Geocoding API health by making a simple search request.
    Uses a shorter timeout for health checks to prevent hanging.
    
    Returns:
        True if API is healthy, False otherwise
    """
    try:
        api_key = settings.MAPTILER_API_KEY
        if not api_key:
            return True

        site_domain = settings.SITE_DOMAIN
        headers = {'Origin': site_domain} if site_domain else {}
        response = requests.get(
            "https://api.maptiler.com/geocoding/test.json",
            params={'key': api_key, 'limit': 1},
            headers=headers,
            timeout=HEALTH_CHECK_EXTERNAL_API_TIMEOUT
        )
        return response.status_code == 200
    except Exception as e:
        # Log unexpected exceptions (network errors are expected, but bugs are not)
        if not isinstance(e, (requests.exceptions.RequestException, requests.exceptions.Timeout)):
            _logger.warning(f"MapTiler Geocoding API health check failed with unexpected exception:\n{traceback.format_exc()}")
        return False


def check_google_geocoding_api() -> bool:
    """
    Check Google Geocoding API health by making a minimal geocode request.
    Uses a shorter timeout for health checks to prevent hanging.

    Returns:
        True if API is healthy, False otherwise
    """
    try:
        api_key = settings.GOOGLE_GEOCODING_API_KEY
        if not api_key:
            return True

        response = requests.get(
            "https://maps.googleapis.com/maps/api/geocode/json",
            params={'address': 'test', 'key': api_key, 'language': 'en'},
            timeout=HEALTH_CHECK_EXTERNAL_API_TIMEOUT
        )
        if response.status_code != 200:
            return False
        data = response.json()
        status = data.get('status')
        return status in ('OK', 'ZERO_RESULTS')
    except Exception as e:
        if not isinstance(e, (requests.exceptions.RequestException, requests.exceptions.Timeout)):
            _logger.warning(
                f"Google Geocoding API health check failed with unexpected exception:\n{traceback.format_exc()}"
            )
        return False


@ensure_db_connection_cleanup
def _run_check_safely(name, check_func):
    """
    Safely run a health check function and return the result.

    Args:
        name: Name of the check component
        check_func: Function to run for the check
        
    Returns:
        Tuple of (name, status_string, is_healthy)
    """
    try:
        result = check_func()
        status = "healthy" if result else "unhealthy"
        return name, status, result
    except Exception:
        # Log exception from health check function
        _logger.warning(f"Health check '{name}' failed with exception:\n{traceback.format_exc()}")
        return name, "unhealthy", False
