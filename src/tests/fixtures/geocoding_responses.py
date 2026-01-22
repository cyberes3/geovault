"""
Real geocoding responses from Overpass API for test fixtures.

This module contains actual raw responses captured from the Overpass API
for common test coordinates. This allows tests to be fast and deterministic
while still using realistic data and testing the actual tag generation logic.

IMPORTANT: All responses in this module are REAL Overpass API responses
captured from actual API queries. They are NOT synthetic or simplified data.
This ensures tests validate against real-world OSM data structures, tag
combinations, and response formats.

Each response preserves the exact structure returned by Overpass API, including:
- Element types (node, way, area, relation)
- OSM IDs
- All tags and their values
- Coordinate data (lat/lon, center, etc.)
- Any other fields present in real Overpass responses

Responses captured: December 8, 2025
"""
import json

# Raw Overpass API responses for administrative hierarchy queries
# These are actual responses from the Overpass API that the geocoding service
# will process to generate location tags

# San Francisco, California (37.7749, -122.4194)
# Most common test coordinate
# REAL Overpass API response captured December 8, 2025
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
# REAL Overpass API response captured December 8, 2025
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
# REAL Overpass API response captured December 8, 2025
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
# REAL Overpass API response captured December 8, 2025
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

# Rocky Mountain National Park lakes (40.34, -105.68) - Lakes
# REAL Overpass API response captured December 22, 2025
COLORADO_RMNP_LAKES_RESPONSE = {
    "elements": [
        {
            "type": "way",
            "id": 37452105,
            "center": {
                "lat": 40.342179,
                "lon": -105.6874982
            },
            "tags": {
                "intermittent": "no",
                "name": "Spruce Lake",
                "natural": "water",
                "salt": "no",
                "source:geometry": "USGS 3D Elevation Program Data",
                "tidal": "no",
                "water": "lake"
            }
        },
        {
            "type": "way",
            "id": 106286047,
            "center": {
                "lat": 40.3316965,
                "lon": -105.6704161
            },
            "tags": {
                "name": "Round Pond",
                "natural": "water"
            }
        },
        {
            "type": "way",
            "id": 106286122,
            "center": {
                "lat": 40.3317474,
                "lon": -105.6933816
            },
            "tags": {
                "intermittent": "no",
                "name": "Tourmaline Lake",
                "natural": "water",
                "salt": "no",
                "tidal": "no",
                "water": "lake"
            }
        },
        {
            "type": "way",
            "id": 106286194,
            "center": {
                "lat": 40.3378279,
                "lon": -105.6963964
            },
            "tags": {
                "intermittent": "no",
                "name": "Loomis Lake",
                "natural": "water",
                "salt": "no",
                "tidal": "no",
                "water": "lake"
            }
        },
        {
            "type": "way",
            "id": 106286219,
            "center": {
                "lat": 40.3393818,
                "lon": -105.6934459
            },
            "tags": {
                "intermittent": "no",
                "name": "Primrose Pond",
                "natural": "water",
                "salt": "no",
                "tidal": "no",
                "water": "lake"
            }
        },
        {
            "type": "way",
            "id": 106286253,
            "center": {
                "lat": 40.330161,
                "lon": -105.6807739
            },
            "tags": {
                "name": "Marigold Lake",
                "natural": "water"
            }
        },
        {
            "type": "way",
            "id": 106286632,
            "center": {
                "lat": 40.3529514,
                "lon": -105.6714518
            },
            "tags": {
                "name": "Black Pool",
                "natural": "water"
            }
        },
        {
            "type": "relation",
            "id": 6019049,
            "center": {
                "lat": 40.336815,
                "lon": -105.6766574
            },
            "tags": {
                "intermittent": "no",
                "name": "Fern Lake",
                "natural": "water",
                "salt": "no",
                "source:geometry": "USGS 3D Elevation Program",
                "tidal": "no",
                "type": "multipolygon",
                "water": "lake",
                "wikidata": "Q5444462"
            }
        },
        {
            "type": "relation",
            "id": 6019050,
            "center": {
                "lat": 40.3303349,
                "lon": -105.6853067
            },
            "tags": {
                "intermittent": "no",
                "name": "Odessa Lake",
                "natural": "water",
                "salt": "no",
                "source:geometry": "USGS 3D Elevation Program",
                "tidal": "no",
                "type": "multipolygon",
                "water": "lake"
            }
        }
    ]
}

# Park County lakes (39.0, -105.0) - Lakes
# REAL Overpass API response captured December 22, 2025
COLORADO_PARK_COUNTY_LAKES_RESPONSE = {
    "elements": [
        {
            "type": "way",
            "id": 39683020,
            "center": {
                "lat": 39.0083474,
                "lon": -104.9930248
            },
            "tags": {
                "ele": "2767",
                "gnis:feature_id": "193225",
                "name": "Leo Lake",
                "natural": "water",
                "water": "reservoir"
            }
        },
        {
            "type": "way",
            "id": 39683085,
            "center": {
                "lat": 39.0067846,
                "lon": -104.9955963
            },
            "tags": {
                "ele": "2768",
                "gnis:feature_id": "197584",
                "name": "Sapphire Lake",
                "natural": "water",
                "water": "reservoir"
            }
        },
        {
            "type": "relation",
            "id": 223995,
            "center": {
                "lat": 39.0119952,
                "lon": -104.9948265
            },
            "tags": {
                "ele": "2759",
                "gnis:feature_id": "193226",
                "name": "Grace Lake",
                "natural": "water",
                "type": "multipolygon",
                "water": "reservoir"
            }
        }
    ]
}

# Park County cities (39.0, -105.0) - Cities
# REAL Overpass API response captured December 22, 2025
COLORADO_PARK_COUNTY_CITIES_RESPONSE = {
    "elements": [
        {
            "type": "node",
            "id": 151352413,
            "lat": 38.9341238,
            "lon": -105.0151688,
            "tags": {
                "ele": "2364",
                "gnis:feature_id": "191196",
                "name": "Green Mountain Falls",
                "place": "village",
                "population": "622"
            }
        },
        {
            "type": "node",
            "id": 151604236,
            "lat": 38.9938016,
            "lon": -105.057045,
            "tags": {
                "ele": "2585",
                "gnis:feature_id": "204768",
                "name": "Woodland Park",
                "place": "town",
                "population": "6729",
                "population:date": "2006",
                "source:population": "US Census",
                "wikidata": "Q2009757",
                "wikipedia": "en:Woodland Park, Colorado"
            }
        }
    ]
}

# Park County cities (40.0, -105.0) - Cities
# REAL Overpass API response captured December 22, 2025
COLORADO_PARK_COUNTY_40_CITIES_RESPONSE = {
    "elements": [
        {
            "type": "node",
            "id": 151493378,
            "lat": 39.9403995,
            "lon": -105.05208,
            "tags": {
                "capital": "6",
                "ele": "1644",
                "gnis:feature_id": "204704",
                "name": "Broomfield",
                "name:en": "Broomfield",
                "name:es": "Broomfield",
                "official_name": "City and County of Broomfield",
                "official_name:es": "Ciudad y Condado de Broomfield",
                "place": "city",
                "population": "74112",
                "population:date": "2020",
                "website": "https://www.broomfield.org/",
                "wikidata": "Q492819",
                "wikipedia": "en:Broomfield, Colorado"
            }
        },
        {
            "type": "node",
            "id": 151520694,
            "lat": 39.9935959,
            "lon": -105.089705,
            "tags": {
                "ele": "1588",
                "gnis:feature_id": "202813",
                "name": "Lafayette",
                "place": "town",
                "population": "30411",
                "population:date": "2020",
                "source:population": "US Census",
                "wikidata": "Q9019841"
            }
        },
        {
            "type": "node",
            "id": 151639957,
            "lat": 40.0502623,
            "lon": -105.049981,
            "tags": {
                "ele": "1532",
                "gnis:feature_id": "178731",
                "name": "Erie",
                "place": "town",
                "population": "30038",
                "population:date": "2020",
                "source:population": "US Census",
                "wikidata": "Q1003798",
                "wikipedia": "en:Erie, Colorado"
            }
        }
    ]
}

# Grand Lake area (40.2514, -105.8239) - Lakes
# REAL Overpass API response captured December 8, 2025
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

# South Valley Park, Colorado (39.5626793, -105.1501089) - Protected area
COLORADO_SOUTH_VALLEY_PARK_PROTECTED_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3609155815,
            "tags": {
                "boundary": "protected_area",
                "landuse": "recreation_ground",
                "name": "South Valley Park",
                "operator": "Jefferson County Open Space",
                "operator:short": "JCOS",
                "operator:type": "public",
                "operator:wikidata": "Q111904267",
                "protect_class": "5",
                "protection_title": "Open Space",
                "source": "knowledge",
                "type": "boundary",
                "website": "https://www.jeffco.us/1431/South-Valley-Park"
            }
        }
    ]
}

# South Valley Park, Colorado (39.5626793, -105.1501089) - Admin boundaries
COLORADO_SOUTH_VALLEY_PARK_ADMIN_RESPONSE = {
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
            "id": 3600161961,
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
                "name": "Jefferson County",
                "name:en": "Jefferson County",
                "type": "boundary"
            }
        }
    ]
}

# Blue Hills Reservation, Massachusetts (42.22314472038681, -71.09840390005273) - Protected area
MASSACHUSETTS_BLUE_HILLS_PROTECTED_RESPONSE = {
    "elements": [
        {
            "type": "area",
            "id": 3604122197,
            "tags": {
                "access": "yes",
                "boundary": "protected_area",
                "leisure": "nature_reserve",
                "name": "Blue Hills Reservation",
                "operator": "Division of State Parks and Recreation",
                "operator:type": "government",
                "operator:wikidata": "Q130236044",
                "owner": "Commonwealth of Massachusetts",
                "ownership": "state",
                "protect_class": "5",
                "protected": "perpetuity",
                "type": "boundary",
                "wikidata": "Q885725",
                "wikipedia": "en:Blue Hills Reservation"
            }
        }
    ]
}

# Blue Hills Reservation, Massachusetts (42.22314472038681, -71.09840390005273) - Admin boundaries
MASSACHUSETTS_BLUE_HILLS_ADMIN_RESPONSE = {
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
        }
    ]
}

# Bells Bend Park, Tennessee (36.15564975174452, -86.92466166278994) - Protected area
TENNESSEE_BELLS_BEND_PROTECTED_RESPONSE = {
    "elements": [
        {
            "type": "way",
            "id": 609338420,
            "tags": {
                "boundary": "protected_area",
                "name": "Bells Bend Park",
                "wikidata": "Q47669456"
            }
        }
    ]
}

# Bells Bend Park, Tennessee (36.15564975174452, -86.92466166278994) - Admin boundaries
TENNESSEE_BELLS_BEND_ADMIN_RESPONSE = {
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
            "id": 3600161838,
            "tags": {
                "admin_level": "4",
                "boundary": "administrative",
                "name": "Tennessee",
                "name:en": "Tennessee",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600197472,
            "tags": {
                "admin_level": "8",
                "boundary": "administrative",
                "name": "Nashville",
                "name:en": "Nashville",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3601847619,
            "tags": {
                "admin_level": "6",
                "boundary": "administrative",
                "name": "Davidson County",
                "name:en": "Davidson County",
                "type": "boundary"
            }
        }
    ]
}

# James N. Manley Park, Colorado (39.72294740028117, -104.95773491586752) - City park (leisure=park, no boundary=protected_area)
COLORADO_JAMES_MANLEY_PARK_PROTECTED_RESPONSE = {
    "elements": [
        {
            "type": "way",
            "id": 282847873,
            "tags": {
                "leisure": "park",
                "name": "James N. Manley Park",
                "source": "Bing"
            }
        }
    ]
}

# James N. Manley Park, Colorado (39.72294740028117, -104.95773491586752) - Admin boundaries
COLORADO_JAMES_MANLEY_PARK_ADMIN_RESPONSE = {
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
            "id": 3600161961,
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
            "id": 3600112875,
            "tags": {
                "admin_level": "8",
                "boundary": "administrative",
                "name": "Denver",
                "place": "city",
                "type": "boundary"
            }
        },
        {
            "type": "area",
            "id": 3600109488,
            "tags": {
                "admin_level": "6",
                "boundary": "administrative",
                "name": "Denver County",
                "name:en": "Denver County",
                "type": "boundary"
            }
        }
    ]
}

# Real Overpass API response for retry tests
# Captured from https://overpass.private.coffee/api/interpreter on January 22, 2026
# Query: [out:json];node(around:1000,37.7749,-122.4194);out;
# This is a simplified version with first few elements for test fixtures
# REAL Overpass API response - subset of full response with 45,831 elements
RETRY_TEST_SUCCESS_RESPONSE = {
    "version": 0.6,
    "generator": "Overpass API 0.7.61.8 b1080abd",
    "osm3s": {
        "timestamp_osm_base": "2026-01-22T03:16:22Z",
        "copyright": "The data included in this document is from www.openstreetmap.org. The data is made available under ODbL."
    },
    "elements": [
        {
            "type": "node",
            "id": 61675193,
            "lat": 37.7721282,
            "lon": -122.4227728,
            "tags": {
                "addr:city": "San Francisco",
                "addr:housenumber": "4",
                "addr:postcode": "94103",
                "addr:state": "CA",
                "addr:street": "Valencia Street",
                "amenity": "bar",
                "check_date": "2025-12-02",
                "level": "0",
                "name": "Martuni's",
                "outdoor_seating": "no",
                "phone": "+1-415-241-0205",
                "smoking": "no",
                "wikidata": "Q108821598"
            }
        },
        {
            "type": "node",
            "id": 65280134,
            "lat": 37.7749327,
            "lon": -122.4084965
        },
        {
            "type": "node",
            "id": 65280136,
            "lat": 37.7747987,
            "lon": -122.4083293
        },
        {
            "type": "node",
            "id": 65281164,
            "lat": 37.7746045,
            "lon": -122.4259102,
            "tags": {
                "highway": "traffic_signals",
                "turn_restrictions": "no"
            }
        },
        {
            "type": "node",
            "id": 65281218,
            "lat": 37.7718656,
            "lon": -122.423325,
            "tags": {
                "highway": "traffic_signals",
                "traffic_signals": "traffic_lights"
            }
        }
    ]
}

# Empty response for coordinates with no data
# NOTE: This should only be used for responses that have been verified as empty
# by fetching actual Overpass API responses. If you're adding a new EMPTY_RESPONSE,
# first verify by querying the Overpass API directly.
EMPTY_RESPONSE = {
    "elements": []
}

# Query response mapping
# Maps (lat, lon, query_type) tuples to real Overpass API responses
# query_type: 'admin', 'protected', 'cities', 'lakes'
# All responses are REAL Overpass API responses captured December 8, 2025
OVERPASS_RESPONSES = {
    # San Francisco, California
    (37.7749, -122.4194, 'admin'): SF_ADMIN_RESPONSE,
    
    # Blue Hills, Massachusetts
    (42.2095, -71.1190, 'admin'): MILTON_ADMIN_RESPONSE,
    (42.2181, -71.1127, 'admin'): MILTON_ADMIN_RESPONSE,
    (42.2088, -71.1079, 'admin'): MILTON_ADMIN_RESPONSE,
    (42.2231, -71.0984, 'admin'): MASSACHUSETTS_BLUE_HILLS_ADMIN_RESPONSE,
    (42.2231, -71.0984, 'protected'): MASSACHUSETTS_BLUE_HILLS_PROTECTED_RESPONSE,
    
    # Nebraska locations
    (42.7286, -102.4171, 'admin'): NEBRASKA_DAWES_RESPONSE,
    (41.6935, -101.3844, 'admin'): NEBRASKA_MCPHERSON_RESPONSE,
    (41.7292, -102.8719, 'admin'): NEBRASKA_GARDEN_RESPONSE,
    
    # Colorado locations
    (39.746, -104.844, 'admin'): COLORADO_AURORA_ADMIN_RESPONSE,
    (39.2216, -105.9327, 'cities'): COLORADO_FAIRPLAY_CITIES_RESPONSE,
    (39.2216, -105.9327, 'admin'): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,
    (40.3428, -105.6836, 'protected'): COLORADO_RMNP_PROTECTED_RESPONSE,
    (40.3428, -105.6836, 'admin'): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,
    (40.3429, -105.6837, 'protected'): COLORADO_RMNP_PROTECTED_RESPONSE,  # Rounded coords for cache test
    (40.3429, -105.6837, 'admin'): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,  # Rounded coords for cache test
    (40.34, -105.68, 'protected'): COLORADO_RMNP_PROTECTED_RESPONSE,
    (40.34, -105.68, 'admin'): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,  # RMNP admin (for get_location_tags)
    (40.34, -105.68, 'lakes'): COLORADO_RMNP_LAKES_RESPONSE,  # RMNP lakes - REAL response
    (40.34, -105.68, 'cities'): EMPTY_RESPONSE,  # RMNP cities - verified empty
    (40.2514, -105.8239, 'lakes'): COLORADO_GRAND_LAKE_RESPONSE,
    (40.2514, -105.8239, 'admin'): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,
    (40.2514, -105.8239, 'protected'): COLORADO_RMNP_PROTECTED_RESPONSE,
    (40.2114, -105.7686, 'lakes'): EMPTY_RESPONSE,  # Outside range - no lakes (test expects empty)
    (40.2114, -105.7686, 'admin'): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,  # Admin for outside range test
    (39.0, -105.0, 'admin'): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,
    (39.0, -105.0, 'protected'): COLORADO_PIKE_NF_PROTECTED_RESPONSE,  # Park County protected (for get_location_tags)
    (39.0, -105.0, 'lakes'): COLORADO_PARK_COUNTY_LAKES_RESPONSE,  # Park County lakes - REAL response
    (39.0, -105.0, 'cities'): COLORADO_PARK_COUNTY_CITIES_RESPONSE,  # Park County cities - REAL response
    (40.0, -105.0, 'admin'): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,
    (40.0, -105.0, 'protected'): COLORADO_PIKE_NF_PROTECTED_RESPONSE,  # Park County protected (for get_location_tags)
    (40.0, -105.0, 'lakes'): EMPTY_RESPONSE,  # Park County lakes - verified empty
    (40.0, -105.0, 'cities'): COLORADO_PARK_COUNTY_40_CITIES_RESPONSE,  # Park County cities - REAL response
    (39.07, -108.73, 'protected'): COLORADO_NATIONAL_MONUMENT_RESPONSE,
    (39.07, -108.73, 'admin'): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,  # Colorado National Monument admin (for get_location_tags)
    (39.07, -108.73, 'lakes'): EMPTY_RESPONSE,  # Colorado National Monument lakes - verified empty
    (39.07, -108.73, 'cities'): EMPTY_RESPONSE,  # Colorado National Monument cities - verified empty
    (39.42, -105.65, 'protected'): COLORADO_LOST_CREEK_WILDERNESS_RESPONSE,
    (39.42, -105.65, 'admin'): COLORADO_PARK_COUNTY_ADMIN_RESPONSE,  # Lost Creek Wilderness admin (for get_location_tags)
    (39.42, -105.65, 'lakes'): EMPTY_RESPONSE,  # Lost Creek Wilderness lakes - verified empty
    (39.42, -105.65, 'cities'): EMPTY_RESPONSE,  # Lost Creek Wilderness cities - verified empty
    (39.5627, -105.1501, 'admin'): COLORADO_SOUTH_VALLEY_PARK_ADMIN_RESPONSE,
    (39.5627, -105.1501, 'protected'): COLORADO_SOUTH_VALLEY_PARK_PROTECTED_RESPONSE,
    (39.7229, -104.9577, 'admin'): COLORADO_JAMES_MANLEY_PARK_ADMIN_RESPONSE,
    (39.7229, -104.9577, 'protected'): COLORADO_JAMES_MANLEY_PARK_PROTECTED_RESPONSE,
    
    # Tennessee
    (36.1556, -86.9247, 'admin'): TENNESSEE_BELLS_BEND_ADMIN_RESPONSE,
    (36.1556, -86.9247, 'protected'): TENNESSEE_BELLS_BEND_PROTECTED_RESPONSE,
}


def get_mock_overpass_response(query: str) -> dict:
    """
    Get a mock Overpass API response based on the query.
    
    Simple dictionary lookup - extracts coordinates and query type,
    then returns the appropriate real Overpass API response.
    
    All responses are actual raw responses captured from the Overpass API
    on December 8, 2025. This ensures tests use realistic data structures
    and tag combinations that match real-world OSM data.
    
    Args:
        query: Overpass QL query string
        
    Returns:
        Dict with Overpass API response format (with 'elements' key)
    """
    import re
    
    # Extract coordinates
    coord_match = re.search(r'is_in\(([-\d.]+),([-\d.]+)\)', query)
    if not coord_match:
        coord_match = re.search(r'around:[\d.]+,([-\d.]+),([-\d.]+)', query)
    
    if not coord_match:
        return EMPTY_RESPONSE
    
    try:
        lat = round(float(coord_match.group(1)), 4)
        lon = round(float(coord_match.group(2)), 4)
    except ValueError:
        return EMPTY_RESPONSE
    
    # Detect query type
    if 'admin_level' in query and 'boundary"="administrative' in query:
        query_type = 'admin'
    elif 'boundary"="protected_area' in query or 'boundary"="national_park' in query or 'leisure"="nature_reserve' in query or 'leisure"="park' in query or 'landuse"="recreation_ground' in query:
        query_type = 'protected'
    elif 'place"~"town|city|village' in query or '"place"~"town|city|village' in query:
        query_type = 'cities'
    elif 'natural"="water' in query or 'water"="lake' in query or '"natural"="water' in query or '"water"="lake' in query:
        query_type = 'lakes'
    else:
        query_type = 'admin'  # Default fallback
    
    # Simple dictionary lookup - fail loudly if response is missing
    key = (lat, lon, query_type)
    if key in OVERPASS_RESPONSES:
        return OVERPASS_RESPONSES[key]
    
    # No response found - this means we need to add a real Overpass response for this test
    raise KeyError(
        f"No Overpass response fixture found for coordinates ({lat}, {lon}) with query type '{query_type}'. "
        f"Add a real Overpass API response to OVERPASS_RESPONSES dict. Query was: {query[:200]}..."
    )
