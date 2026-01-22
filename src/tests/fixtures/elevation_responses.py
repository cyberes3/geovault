"""
Real elevation API responses for test fixtures.

This module contains actual raw responses captured from the elevation API
(https://elevation.racemap.com/api) for common test coordinates. This allows tests
to be fast and deterministic while still using realistic data.

IMPORTANT: All responses in this module are REAL elevation API responses
captured from actual API queries. They are NOT synthetic or simplified data.
This ensures tests validate against real-world elevation data.

Each response preserves the exact structure returned by the elevation API:
- Array of elevation values in meters (floats)
- One elevation value per coordinate pair in the request
- Values are in meters above sea level

Responses captured: January 22, 2026
"""

# Real elevation API response for San Francisco coordinates (2 points)
# Captured from https://elevation.racemap.com/api on January 22, 2026
# Request: POST [[37.7749, -122.4194], [37.7849, -122.4094]]
# Response: [16, 14] (elevation in meters)
ELEVATION_TEST_SUCCESS_RESPONSE_SF = [16.0, 14.0]

# Real elevation API response for San Francisco single coordinate
# Captured from https://elevation.racemap.com/api on January 22, 2026
# Request: POST [[37.7749, -122.4194]]
# Response: [16] (elevation in meters)
ELEVATION_TEST_SUCCESS_RESPONSE_SINGLE = [16.0]
