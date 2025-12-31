"""Format encoding utilities for bbox query responses.

Supports both JSON and geobuf (protobuf) formats with content negotiation
via query parameters or Accept headers.
"""
import gzip
import logging

import geobuf
from django.http import JsonResponse, HttpResponse

logger = logging.getLogger(__name__)

# Format constants
FORMAT_JSON = 'json'
FORMAT_PROTOBUF = 'protobuf'

# Content types
CONTENT_TYPE_JSON = 'application/json'
CONTENT_TYPE_PROTOBUF = 'application/x-protobuf'

# Geobuf encoding settings
# Precision: 5 decimal places = ~1.1 meters accuracy (good for map display)
# Lower precision = smaller file size
GEOBUF_PRECISION = 5
GEOBUF_DIMENSIONS = 2  # 2D coordinates (lat, lon)


def detect_response_format(request) -> str:
    """
    Detect the desired response format from request.
    
    Checks in order:
    1. Query parameter: ?format=protobuf or ?format=json
    2. Accept header: application/x-protobuf or application/json
    3. Defaults to JSON
    
    Args:
        request: Django HttpRequest object
        
    Returns:
        Format string: 'json' or 'protobuf'
    """
    # Check query parameter first (explicit preference)
    format_param = request.GET.get('format', '').lower()
    if format_param == FORMAT_PROTOBUF:
        return FORMAT_PROTOBUF
    if format_param == FORMAT_JSON:
        return FORMAT_JSON

    # Check Accept header
    accept_header = request.META.get('HTTP_ACCEPT', '')
    if CONTENT_TYPE_PROTOBUF in accept_header:
        return FORMAT_PROTOBUF
    if CONTENT_TYPE_JSON in accept_header:
        return FORMAT_JSON

    # Default to JSON
    return FORMAT_JSON


def encode_bbox_response(response_data: dict, format_type: str) -> tuple:
    """
    Encode bbox response data based on format.
    
    Args:
        response_data: Dictionary with 'data' (GeoJSON FeatureCollection) and metadata
        format_type: 'json' or 'protobuf'
        
    Returns:
        For JSON: (response_data_dict, None)
        For Protobuf: (geobuf_bytes, headers_dict)
        
    Raises:
        ValueError: If format is invalid
        RuntimeError: If geobuf encoding fails
    """
    if format_type == FORMAT_JSON:
        return response_data, None

    if format_type == FORMAT_PROTOBUF:
        # Extract GeoJSON FeatureCollection
        geojson_data = response_data.get('data', {})
        if not geojson_data or geojson_data.get('type') != 'FeatureCollection':
            raise ValueError("Response data must contain a GeoJSON FeatureCollection")

        # Encode to geobuf with reduced precision for better compression
        # geobuf.encode() accepts precision and dim as positional arguments
        geobuf_bytes = geobuf.encode(geojson_data, GEOBUF_PRECISION, GEOBUF_DIMENSIONS)

        # Build metadata headers
        headers = {}

        # Standard metadata fields
        if 'feature_count' in response_data:
            headers['X-Feature-Count'] = str(response_data['feature_count'])
        if 'total_features_in_bbox' in response_data:
            headers['X-Total-Features-In-Bbox'] = str(response_data['total_features_in_bbox'])
        if 'max_features_limit' in response_data:
            headers['X-Max-Features-Limit'] = str(response_data['max_features_limit'])
        if 'zoom_level' in response_data:
            headers['X-Zoom-Level'] = str(response_data['zoom_level'])
        if 'fallback_used' in response_data:
            headers['X-Fallback-Used'] = 'true' if response_data['fallback_used'] else 'false'
        if 'timestamp' in response_data:
            headers['X-Timestamp'] = str(response_data['timestamp'])

        # Extra fields (e.g., collection_name, warning)
        for key, value in response_data.items():
            if key not in ('data', 'feature_count', 'total_features_in_bbox',
                           'max_features_limit', 'zoom_level', 'fallback_used', 'timestamp'):
                # Convert key to header format (e.g., collection_name -> X-Collection-Name)
                header_key = 'X-' + '-'.join(word.capitalize() for word in key.split('_'))
                headers[header_key] = str(value)

        return geobuf_bytes, headers

    raise ValueError(f"Invalid format: {format_type}. Must be 'json' or 'protobuf'")


def create_bbox_response(response_data: dict, request) -> HttpResponse:
    """
    Create appropriate HttpResponse based on format detected from request.
    
    Args:
        response_data: Dictionary with 'data' (GeoJSON FeatureCollection) and metadata
        request: Django HttpRequest object
        
    Returns:
        JsonResponse for JSON format, HttpResponse with geobuf bytes for protobuf format
    """
    format_type = detect_response_format(request)
    encoded_data, headers = encode_bbox_response(response_data, format_type)
    
    if format_type == FORMAT_JSON:
        return JsonResponse(encoded_data)
    
    if format_type == FORMAT_PROTOBUF:
        # Always compress with gzip for maximum compression
        compressed_data = gzip.compress(encoded_data, compresslevel=9)  # Maximum compression
        response = HttpResponse(compressed_data, content_type=CONTENT_TYPE_PROTOBUF)
        response['Content-Encoding'] = 'gzip'
        
        # Set metadata headers
        if headers:
            for key, value in headers.items():
                response[key] = value
        return response
    
    # Should not reach here, but fallback to JSON
    return JsonResponse(response_data)
