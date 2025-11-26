#!/usr/bin/env python3
"""
Test script for reverse geocoding functionality.
Takes coordinates (latitude, longitude) and returns location tags.

Usage:
    python test_geocoding.py <latitude> <longitude>
    python test_geocoding.py 39.7392 -104.9903  # Denver
    python test_geocoding.py 39.1591360 -105.2915346  # Pike National Forest
"""

import sys
import os
import django

# Add the backend directory to the path so we can import geo_lib and website
backend_dir = os.path.dirname(os.path.dirname(os.path.dirname(__file__)))
sys.path.insert(0, backend_dir)

# Setup Django
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'website.settings')
django.setup()

from geo_lib.geolocation.reverse_geocode import get_reverse_geocoding_service


def test_geocoding(latitude: float, longitude: float):
    """
    Test geocoding for given coordinates.
    
    Args:
        latitude: Latitude in decimal degrees
        longitude: Longitude in decimal degrees
    """
    print(f"\n{'='*60}")
    print(f"Testing geocoding for coordinates: {latitude}, {longitude}")
    print(f"{'='*60}\n")
    
    service = get_reverse_geocoding_service()
    
    # Get location tags
    tags = service.get_location_tags(latitude, longitude)
    
    print("Generated Tags:")
    print("-" * 60)
    if tags:
        for tag in tags:
            print(f"  • {tag}")
    else:
        print("  (no tags generated)")
    
    print("\n" + "-" * 60)
    
    # Also show detailed reverse geocoding info
    print("\nDetailed Location Information:")
    print("-" * 60)
    location_data = service.reverse_geocode(latitude, longitude)
    if location_data:
        for key, value in location_data.items():
            if value:
                print(f"  {key}: {value}")
    else:
        print("  (no location data available)")
    
    # Show protected areas
    print("\nProtected Areas:")
    print("-" * 60)
    protected_areas = service.search_protected_areas(latitude, longitude)
    if protected_areas:
        for area in protected_areas:
            print(f"  • {area.get('name', 'Unknown')} ({area.get('type', 'unknown type')})")
    else:
        print("  (no protected areas found)")
    
    # Show city proximity
    print("\nCity Proximity Check:")
    print("-" * 60)
    city_prox = service.check_city_proximity(latitude, longitude, 5.0)
    if city_prox:
        print(f"  Nearest city: {city_prox.get('name', 'Unknown')}")
        print(f"  Distance: {city_prox.get('distance_miles', 0):.2f} miles")
        print(f"  State: {city_prox.get('admin1', 'Unknown')}")
    else:
        print("  (no nearby city within 5 miles)")
    
    # Show lakes
    print("\nLakes/Water Bodies:")
    print("-" * 60)
    lakes = service.search_lakes(latitude, longitude)
    if lakes:
        for lake in lakes:
            print(f"  • {lake.get('name', 'Unknown')} ({lake.get('water_type', 'water')})")
    else:
        print("  (no lakes found)")
    
    print(f"\n{'='*60}\n")


def main():
    """Main entry point."""
    if len(sys.argv) != 3:
        print("Usage: python test_geocoding.py <latitude> <longitude>")
        print("\nExamples:")
        print("  python test_geocoding.py 39.7392 -104.9903  # Denver, CO")
        print("  python test_geocoding.py 39.1591360 -105.2915346  # Pike National Forest")
        print("  python test_geocoding.py 40.7128 -74.0060  # New York City")
        print("  python test_geocoding.py 37.7749 -122.4194  # San Francisco (coastal)")
        sys.exit(1)
    
    try:
        latitude = float(sys.argv[1])
        longitude = float(sys.argv[2])
    except ValueError:
        print("Error: Latitude and longitude must be valid numbers")
        sys.exit(1)
    
    # Validate coordinates
    if not (-90 <= latitude <= 90):
        print("Error: Latitude must be between -90 and 90")
        sys.exit(1)
    
    if not (-180 <= longitude <= 180):
        print("Error: Longitude must be between -180 and 180")
        sys.exit(1)
    
    test_geocoding(latitude, longitude)


if __name__ == '__main__':
    main()

