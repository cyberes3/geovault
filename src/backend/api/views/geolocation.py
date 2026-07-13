"""
API endpoints for IP-based geolocation services.
"""

from django.views.decorators.http import require_http_methods

from api.utils.responses import error_response, success_response
from geo_lib.ip_geolocation import get_geolocation_service
from geo_lib.logging.console import get_tagged_logger
from website.auth_decorators import api_or_login_required_401

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_user_location(request):
    """
    API endpoint to get user location based on their IP address.
    
    Returns:
        JSON with ``location`` object (city, state, country, latitude, longitude) when known,
        or ``location: null`` with HTTP 200 when the IP cannot be resolved (e.g. private/local).
    """
    # Get the geolocation service
    geo_service = get_geolocation_service()

    # Extract client IP
    client_ip = geo_service.get_client_ip(request)

    # Get location data
    location_data = geo_service.get_location_from_ip(client_ip)

    if location_data is None:
        return success_response({'location': None})

    # Prepare response data - only include fields used by frontend
    response_data = {
        'location': {
            'city': location_data.get('city'),
            'state': location_data.get('state'),
            'country': location_data.get('country'),
            'latitude': location_data.get('latitude'),
            'longitude': location_data.get('longitude')
        }
    }

    return success_response(response_data)


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_location_by_ip(request):
    """
    API endpoint to get location for a specific IP address.
    Requires authentication.
    
    Query parameters:
    - ip: IP address to look up (optional, defaults to client IP)
    
    Returns:
        JSON response with location information
    """
    geo_service = get_geolocation_service()

    ip_address = request.GET.get('ip')
    if not ip_address:
        ip_address = geo_service.get_client_ip(request)

    if not ip_address or len(ip_address.split('.')) != 4:
        return error_response('Invalid IP address format', code=400)

    location_data = geo_service.get_location_from_ip(ip_address)

    if location_data is None:
        _logger.error(
            "Location by IP lookup returned no result (ip=%s, path=%s)",
            ip_address,
            request.path,
        )
        return error_response(
            f'Location not found for IP address: {ip_address}',
            code=500,
            details={'location': None, 'ip_info': {'ip': ip_address, 'accuracy_radius': None}},
        )

    # Prepare response data
    response_data = {
        'location': {
            'city': location_data.get('city'),
            'state': location_data.get('state'),
            'state_code': location_data.get('state_code'),
            'country': location_data.get('country'),
            'country_code': location_data.get('country_code'),
            'latitude': location_data.get('latitude'),
            'longitude': location_data.get('longitude'),
        },
        'ip_info': {
            'ip': location_data.get('ip'),
            'accuracy_radius': location_data.get('accuracy_radius')
        }
    }

    _logger.info(f"Location lookup successful for IP {ip_address}")
    return success_response(response_data)
