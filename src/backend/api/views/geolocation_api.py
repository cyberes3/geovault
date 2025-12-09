"""
API endpoints for IP-based geolocation services.
"""
import traceback

from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from geo_lib.ip_service import get_geolocation_service
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import api_or_login_required_401

logger = get_access_logger()


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_user_location(request):
    """
    API endpoint to get user location based on their IP address.
    
    Returns:
        JSON response with location information including:
        - city, state, country
        - latitude, longitude for map centering
    """
    try:
        # Get the geolocation service
        geo_service = get_geolocation_service()

        # Extract client IP
        client_ip = geo_service.get_client_ip(request)

        # Get location data
        location_data = geo_service.get_location_from_ip(client_ip)

        if location_data is None:
            return JsonResponse({
                'error': 'Unable to determine location from IP address',
                'location': None
            }, status=404)

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

        return JsonResponse(response_data)

    except Exception as e:
        logger.error(f"Error in get_user_location API: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Internal server error occurred',
            'code': 500
        }, status=500)


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
    try:
        # Get the geolocation service
        geo_service = get_geolocation_service()

        # Get IP from query parameter or client IP
        ip_address = request.GET.get('ip')
        if not ip_address:
            ip_address = geo_service.get_client_ip(request)

        # Validate IP format (basic validation)
        if not ip_address or len(ip_address.split('.')) != 4:
            return JsonResponse({
                'error': 'Invalid IP address format',
                'code': 400
            }, status=400)

        # Get location data
        location_data = geo_service.get_location_from_ip(ip_address)

        if location_data is None:
            return JsonResponse({
                'error': f'Location not found for IP address: {ip_address}',
                'location': None,
                'ip_info': {
                    'ip': ip_address,
                    'accuracy_radius': None
                }
            }, status=404)

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

        logger.info(f"Location lookup successful for IP {ip_address}")
        return JsonResponse(response_data)

    except Exception as e:
        logger.error(f"Error in get_location_by_ip API: {traceback.format_exc()}")
        return JsonResponse({
            'error': 'Internal server error occurred',
            'code': 500
        }, status=500)
