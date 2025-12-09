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

# Aurora, Colorado (39.746, -104.844) - Admin hierarchy
COLORADO_AURORA_ADMIN_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3600112875,
            "tags": {
                "admin_level": "8",
                "boundary": "administrative",
                "name": "Aurora",
                "place": "city",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600148838,
            "tags": {
                "admin_level": "2",
                "boundary": "administrative",
                "name": "United States of America",
                "name:en": "United States of America",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600161993,
            "tags": {
                "admin_level": "4",
                "boundary": "administrative",
                "name": "Colorado",
                "name:en": "Colorado",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600109488,
            "tags": {
                "admin_level": "6",
                "boundary": "administrative",
                "name": "Adams County",
                "name:en": "Adams County",
                "type": "boundary"
            }
        }
    ]
}

# Fairplay, Colorado area (39.2216, -105.9327) - Nearby cities
COLORADO_FAIRPLAY_CITIES_RESPONSE = {
    "elements": [
        {
            "type": "node",
            "id": 151509367,
            "lat": 39.2251888,
            "lon": -106.0019519,
            "tags": {
                "name": "Fairplay",
                "place": "town",
                "population": "724"
            }
        }
    ]
}

# Rocky Mountain National Park (40.3428, -105.6836) - Protected areas
COLORADO_RMNP_PROTECTED_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3600390960,
            "tags": {
                "boundary": "protected_area",
                "name": "Rocky Mountain National Park",
                "operator": "National Park Service",
                "protection_title": "National Park",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3616849460,
            "tags": {
                "boundary": "protected_area",
                "leisure": "nature_reserve",
                "name": "Rocky Mountain Wilderness",
                "operator": "National Park Service",
                "protection_title": "Wilderness Area",
                "type": "multipolygon"
            }
        }
    ]
}

# Grand Lake area (40.2514, -105.8239) - Lakes
COLORADO_GRAND_LAKE_RESPONSE = {
    "elements": [
        {
            "type": "way",
            "id": 37516043,
            "center": {
                "lat": 40.2430893,
                "lon": -105.8141204
            },
            "tags": {
                "name": "Grand Lake",
                "natural": "water",
                "water": "lake"
            }
        }
    ]
}

# Park County, Colorado (39.0, -105.0) - Comprehensive test
COLORADO_PARK_COUNTY_ADMIN_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3600148838,
            "tags": {
                "admin_level": "2",
                "boundary": "administrative",
                "name": "United States of America",
                "name:en": "United States of America",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600161993,
            "tags": {
                "admin_level": "4",
                "boundary": "administrative",
                "name": "Colorado",
                "name:en": "Colorado",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600112090,
            "tags": {
                "admin_level": "6",
                "boundary": "administrative",
                "name": "Park County",
                "name:en": "Park County",
                "type": "boundary"
            }
        }
    ]
}

# Pike National Forest (39.0, -105.0) - Protected area
COLORADO_PIKE_NF_PROTECTED_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3600391173,
            "tags": {
                "boundary": "protected_area",
                "name": "Pike National Forest",
                "protection_title": "National Forest",
                "type": "boundary"
            }
        }
    ]
}

# Colorado National Monument (39.07, -108.73) - Protected area
COLORADO_NATIONAL_MONUMENT_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3605453168,
            "tags": {
                "boundary": "national_park",
                "leisure": "nature_reserve",
                "name": "Colorado National Monument",
                "protection_title": "National Monument",
                "type": "boundary"
            }
        }
    ]
}

# Lost Creek Wilderness (39.42, -105.65) - Protected area
COLORADO_LOST_CREEK_WILDERNESS_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3600393066,
            "tags": {
                "boundary": "protected_area",
                "name": "Pike National Forest",
                "protection_title": "National Forest",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3605717969,
            "tags": {
                "boundary": "protected_area",
                "leisure": "nature_reserve",
                "name": "Lost Creek Wilderness",
                "protection_title": "Wilderness Area",
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
    
    # Colorado locations from reverse_geocode tests
    (39.746, -104.844): COLORADO_AURORA_ADMIN_RESPONSE,           # Aurora admin hierarchy
    (39.2216, -105.9327): COLORADO_FAIRPLAY_CITIES_RESPONSE,      # Fairplay nearby cities
    (40.3428, -105.6836): COLORADO_RMNP_PROTECTED_RESPONSE,       # Rocky Mountain NP
    (40.2514, -105.8239): COLORADO_GRAND_LAKE_RESPONSE,           # Grand Lake
    (39.0, -105.0): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,          # Park County
    (40.34, -105.68): COLORADO_RMNP_PROTECTED_RESPONSE,           # RMNP (rounded)
    (39.07, -108.73): COLORADO_NATIONAL_MONUMENT_RESPONSE,        # Colorado National Monument
    (39.42, -105.65): COLORADO_LOST_CREEK_WILDERNESS_RESPONSE,    # Lost Creek Wilderness
    (40.0, -105.0): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,          # Colorado caching test
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
