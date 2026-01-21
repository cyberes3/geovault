from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from geo_lib.website.auth import api_or_login_required_401
from website.config_loader import get_config_loader
from website.settings_utils import get_required_setting
from website.startup_checks import (
    check_database_connection,
    check_redis_connection,
    check_postgis_installation,
)


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
        # Get configuration once
        config = get_config_loader()

        # Build list of checks to run in parallel
        # Always run critical health checks (suppress verbose logging)
        checks_to_run = [
            ("database", lambda: check_database_connection(suppress_logging=True)),
            ("redis", lambda: check_redis_connection(suppress_logging=True)),
            ("postgis", lambda: check_postgis_installation(suppress_logging=True))
        ]

        # Check Overpass API only if reverse geocoding is enabled
        reverse_geocoding_enabled = config.get_bool('reverse_geocoding.enabled', True)
        if reverse_geocoding_enabled:
            checks_to_run.append(("overpass_api", check_overpass_api))
        else:
            components["overpass_api"] = "disabled"

        # Always check Elevation API (it will return True if disabled)
        elevation_enabled = get_required_setting('ELEVATION_API_ENABLED')
        if not elevation_enabled:
            components["elevation_api"] = "disabled"
        else:
            checks_to_run.append(("elevation_api", check_elevation_api))

        # Check MapTiler Geocoding API only if API key is set
        maptiler_api_key = config.get_maptiler_api_key()
        if maptiler_api_key:
            checks_to_run.append(("maptiler_geocoding_api", check_maptiler_geocoding_api))
        else:
            components["maptiler_geocoding_api"] = "not_configured"

        # Run all checks in parallel using ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=len(checks_to_run)) as executor:
            # Submit all checks
            future_to_check = {
                executor.submit(_run_check_safely, name, check_func): name
                for name, check_func in checks_to_run
            }

            # Collect results as they complete
            for future in as_completed(future_to_check):
                name, status, is_healthy = future.result()
                components[name] = status
                if not is_healthy:
                    overall_healthy = False

        status = "healthy" if overall_healthy else "unhealthy"
        status_code = 200 if overall_healthy else 500

        return JsonResponse({
            "status": status,
            "components": components
        }, status=status_code)

    except Exception:
        # Any exception means unhealthy
        return JsonResponse({
            "status": "unhealthy",
            "components": components
        }, status=500)


def check_overpass_api() -> bool:
    """
    Check Overpass API health by making a minimal query.
    
    Uses a simple node ID lookup (node 1 exists in OSM) to avoid spatial indexing overhead.
    
    Returns:
        True if API is healthy, False otherwise
    """
    try:
        api_url = get_required_setting('OVERPASS_API_URL')
        api_timeout = get_required_setting('OVERPASS_API_TIMEOUT')
        api_verify_ssl = get_required_setting('OVERPASS_API_VERIFY_SSL')

        # Query node 1 (a well-known OSM node) - direct ID lookup, no spatial search
        response = requests.post(
            api_url,
            data="[out:json];node(1);out;",
            timeout=api_timeout,
            headers={'Content-Type': 'application/x-www-form-urlencoded'},
            verify=api_verify_ssl
        )

        # API is healthy if we get a 200 response (even if no results)
        return response.status_code == 200
    except:
        return False


def check_elevation_api() -> bool:
    """
    Check Elevation API health by making a simple request.
    
    Returns:
        True if API is healthy, False otherwise
    """
    try:
        if not get_required_setting('ELEVATION_API_ENABLED'):
            # If disabled, consider it healthy (not a failure)
            return True
        api_url = get_required_setting('ELEVATION_API_URL')
        api_timeout = get_required_setting('ELEVATION_API_TIMEOUT')

        response = requests.post(
            api_url,
            json=[[0.0, 0.0]],
            headers={'Content-Type': 'application/json'},
            timeout=api_timeout
        )

        if response.status_code == 200:
            data = response.json()
            return isinstance(data, list) and len(data) > 0

        return False
    except:
        return False


def check_maptiler_geocoding_api() -> bool:
    """
    Check MapTiler Geocoding API health by making a simple search request.
    
    Returns:
        True if API is healthy, False otherwise
    """
    try:
        config = get_config_loader()
        api_key = config.get_maptiler_api_key()
        if not api_key:
            return True

        site_domain = config.get_str('site.domain', '')
        headers = {'Origin': site_domain} if site_domain else {}
        response = requests.get(
            "https://api.maptiler.com/geocoding/test.json",
            params={'key': api_key, 'limit': 1},
            headers=headers,
            timeout=5
        )
        return response.status_code == 200
    except Exception:
        return False


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
        return name, "unhealthy", False
