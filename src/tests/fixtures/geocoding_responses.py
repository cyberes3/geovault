"""
Real geocoding responses from Overpass API for test fixtures.

This module contains actual raw responses captured from the Overpass API
for common test coordinates. This allows tests to be fast and deterministic
while still using realistic data and testing the actual tag generation logic.

Responses captured: December 8, 2025
"""
import json

# Raw Overpass API responses for administrative hierarchy queries
# These are actual responses from the Overpass API that the geocoding service
# will process to generate location tags

# San Francisco, California (37.7749, -122.4194)
# Most common test coordinate
SF_ADMIN_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3600148838,
            "tags": {
                "admin_level": "2",
                "boundary": "administrative",
                "flag": "https://upload.wikimedia.org/wikipedia/en/a/a4/Flag_of_the_United_States.svg",
                "name": "United States",
                "name:en": "United States",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600165475,
            "tags": {
                "admin_level": "4",
                "boundary": "administrative",
                "name": "California",
                "name:en": "California",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600111968,
            "tags": {
                "admin_level": "6",
                "boundary": "administrative",
                "name": "San Francisco",
                "name:en": "San Francisco",
                "type": "boundary"
            }
        }
    ]
}

# Milton, Massachusetts (42.2095, -71.1190) - Blue Hills area
MILTON_ADMIN_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3600148838,
            "tags": {
                "admin_level": "2",
                "boundary": "administrative",
                "name": "United States",
                "name:en": "United States",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600061315,
            "tags": {
                "admin_level": "4",
                "boundary": "administrative",
                "name": "Massachusetts",
                "name:en": "Massachusetts",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600062959,
            "tags": {
                "admin_level": "6",
                "boundary": "administrative",
                "name": "Norfolk County",
                "name:en": "Norfolk County",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600362049,
            "tags": {
                "admin_level": "8",
                "boundary": "administrative",
                "name": "Milton",
                "name:en": "Milton",
                "type": "boundary"
            }
        }
    ]
}

# Dawes County, Nebraska (42.7286, -102.4171)
NEBRASKA_DAWES_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3600148838,
            "tags": {
                "admin_level": "2",
                "boundary": "administrative",
                "name": "United States",
                "name:en": "United States",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600161991,
            "tags": {
                "admin_level": "4",
                "boundary": "administrative",
                "name": "Nebraska",
                "name:en": "Nebraska",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600122837,
            "tags": {
                "admin_level": "6",
                "boundary": "administrative",
                "name": "Dawes County",
                "name:en": "Dawes County",
                "type": "boundary"
            }
        }
    ]
}

# McPherson County, Nebraska (41.6935, -101.3844)
NEBRASKA_MCPHERSON_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3600148838,
            "tags": {
                "admin_level": "2",
                "boundary": "administrative",
                "name": "United States",
                "name:en": "United States",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600161991,
            "tags": {
                "admin_level": "4",
                "boundary": "administrative",
                "name": "Nebraska",
                "name:en": "Nebraska",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600122906,
            "tags": {
                "admin_level": "6",
                "boundary": "administrative",
                "name": "McPherson County",
                "name:en": "McPherson County",
                "type": "boundary"
            }
        }
    ]
}

# Garden County, Nebraska (41.7292, -102.8719)
NEBRASKA_GARDEN_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3600148838,
            "tags": {
                "admin_level": "2",
                "boundary": "administrative",
                "name": "United States",
                "name:en": "United States",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600161991,
            "tags": {
                "admin_level": "4",
                "boundary": "administrative",
                "name": "Nebraska",
                "name:en": "Nebraska",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600122854,
            "tags": {
                "admin_level": "6",
                "boundary": "administrative",
                "name": "Garden County",
                "name:en": "Garden County",
                "type": "boundary"
            }
        }
    ]
}

# Empty response for coordinates with no data
EMPTY_RESPONSE = {
    "elements": []
}

# Coordinate -> Response mapping
# Maps (lat, lon) tuples to Overpass API responses
OVERPASS_FIXTURES = {
    # San Francisco
    (37.7749, -122.4194): SF_ADMIN_RESPONSE,
    
    # Blue Hills, Massachusetts
    (42.2095, -71.1190): MILTON_ADMIN_RESPONSE,
    (42.2181, -71.1127): MILTON_ADMIN_RESPONSE,
    (42.2088, -71.1079): MILTON_ADMIN_RESPONSE,
    
    # Nebraska locations from Test Items.kml
    (42.7286, -102.4171): NEBRASKA_DAWES_RESPONSE,
    (41.6935, -101.3844): NEBRASKA_MCPHERSON_RESPONSE,
    (41.7292, -102.8719): NEBRASKA_GARDEN_RESPONSE,
}


def get_mock_overpass_response(query: str) -> dict:
    """
    Get a mock Overpass API response based on the query.
    
    Extracts coordinates from the query and returns the appropriate
    real Overpass response for those coordinates.
    
    Args:
        query: Overpass QL query string
        
    Returns:
        Dict with Overpass API response format (with 'elements' key)
    """
    # Extract coordinates from query
    # Format: is_in(LAT,LON) or similar
    import re
    coord_match = re.search(r'is_in\(([-\d.]+),([-\d.]+)\)', query)
    if not coord_match:
        # Also try around() format: around:DISTANCE,LAT,LON
        coord_match = re.search(r'around:[\d.]+,([-\d.]+),([-\d.]+)', query)
    
    if not coord_match:
        return EMPTY_RESPONSE
    
    lat_str, lon_str = coord_match.groups()
    try:
        lat = float(lat_str)
        lon = float(lon_str)
    except ValueError:
        return EMPTY_RESPONSE
    
    # Round to 4 decimal places for lookup (about 11 meters precision)
    lat_rounded = round(lat, 4)
    lon_rounded = round(lon, 4)
    
    # Try exact match first
    key = (lat_rounded, lon_rounded)
    if key in OVERPASS_FIXTURES:
        return OVERPASS_FIXTURES[key]
    
    # Try nearby matches (within 0.01 degrees, about 1km)
    # This helps catch minor coordinate variations in test data
    for (fixture_lat, fixture_lon), response in OVERPASS_FIXTURES.items():
        if abs(fixture_lat - lat_rounded) < 0.01 and abs(fixture_lon - lon_rounded) < 0.01:
            return response
    
    # No match found - return empty response
    return EMPTY_RESPONSE
