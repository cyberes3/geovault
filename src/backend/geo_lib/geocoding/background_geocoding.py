"""
Background reverse geocoding service for features.

This module provides asynchronous reverse geocoding functionality that allows
features to be saved immediately while reverse geocoding tags are generated in
the background.
"""
import threading
import traceback

from django.db import transaction

from api.models import FeatureStore
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.tagging.modules.geocoding import ReverseGeocodingTagGenerator
from geo_lib.types.feature import (
    PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature
)

_logger = get_tagged_logger('BackgroundReverseGeocoding')


def _get_feature_class_from_geojson(geojson: dict):
    """
    Determine the appropriate feature class from GeoJSON geometry type.
    
    Args:
        geojson: GeoJSON feature dictionary
        
    Returns:
        Feature class (PointFeature, LineStringFeature, etc.) or None
    """
    geometry_type = geojson.get('geometry', {}).get('type', '').lower()

    if geometry_type in ['point', 'multipoint']:
        return PointFeature
    elif geometry_type == 'linestring':
        return LineStringFeature
    elif geometry_type == 'multilinestring':
        return MultiLineStringFeature
    elif geometry_type in ['polygon', 'multipolygon']:
        return PolygonFeature

    return None


def reverse_geocode_feature_async(feature_id: int):
    """
    Perform reverse geocoding for a feature in a background thread.
    
    This function:
    1. Locks the feature row using select_for_update()
    2. Generates reverse geocoding tags using ReverseGeocodingTagGenerator
    3. Updates the feature's system_tags in the geojson field
    4. Saves the updated feature
    
    Errors are logged silently and do not affect the feature.
    
    Args:
        feature_id: The ID of the FeatureStore record to reverse geocode
    """

    def _reverse_geocode_worker():
        try:
            # Use a database transaction to ensure atomicity
            with transaction.atomic():
                # Lock the row to prevent concurrent modifications
                try:
                    feature_store = FeatureStore.objects.select_for_update().get(id=feature_id)
                except FeatureStore.DoesNotExist:
                    _logger.warning(f"Feature {feature_id} not found for background reverse geocoding")
                    return

                # Get the geojson data
                geojson = feature_store.geojson
                if not geojson:
                    _logger.warning(f"Feature {feature_id} has no geojson data")
                    return

                # Determine the appropriate feature class
                feature_class = _get_feature_class_from_geojson(geojson)
                if not feature_class:
                    _logger.warning(f"Feature {feature_id} has unsupported geometry type for reverse geocoding")
                    return

                geojson_for_validation = geojson.copy()
                geojson_for_validation['properties']['geojson_hash'] = feature_store.geojson_hash
                feature_instance = feature_class(**geojson_for_validation)

                # Generate reverse geocoding tags using the tag generator
                reverse_geocoding_generator = ReverseGeocodingTagGenerator()
                try:
                    reverse_geocoding_tags = reverse_geocoding_generator.process(feature_instance, import_log=None)
                except:
                    _logger.warning(f"Reverse geocoding tag generation failed for feature {feature_id}: {traceback.format_exc()}")
                    return

                geojson.setdefault('properties', {})
                geojson['properties']['system_tags'] = list(set(geojson['properties'].get('system_tags', []) + reverse_geocoding_tags))
                # Ensure the hash in properties matches the model field hash
                geojson['properties']['geojson_hash'] = feature_store.geojson_hash

                feature_store.geojson = geojson
                feature_store.save(update_fields=['geojson'])
        except:
            # Log error but don't raise - this is background processing
            _logger.error(f"Error in background reverse geocoding for feature {feature_id}: {traceback.format_exc()}")

    # Start the reverse geocoding in a background thread
    thread = threading.Thread(target=_reverse_geocode_worker, daemon=True, name=f"ReverseGeocodeFeature-{feature_id}")
    thread.start()
    _logger.debug(f"Started background reverse geocoding thread for feature {feature_id}")
