"""
Background geocoding service for features.

This module provides asynchronous geocoding functionality that allows
features to be saved immediately while geocoding tags are generated in
the background.
"""
import threading
import traceback

from django.db import transaction

from api.models import FeatureStore
from geo_lib.logging.console import get_job_logger
from geo_lib.processing.tagging.modules.geocoding import GeocodingTagGenerator
from geo_lib.types.feature import (
    PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature
)

logger = get_job_logger()


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


def geocode_feature_async(feature_id: int):
    """
    Perform geocoding for a feature in a background thread.
    
    This function:
    1. Locks the feature row using select_for_update()
    2. Generates geocoding tags using GeocodingTagGenerator
    3. Updates the feature's system_tags in the geojson field
    4. Saves the updated feature
    
    Errors are logged silently and do not affect the feature.
    
    Args:
        feature_id: The ID of the FeatureStore record to geocode
    """

    def _geocode_worker():
        try:
            # Use a database transaction to ensure atomicity
            with transaction.atomic():
                # Lock the row to prevent concurrent modifications
                try:
                    feature_store = FeatureStore.objects.select_for_update().get(id=feature_id)
                except FeatureStore.DoesNotExist:
                    logger.warning(f"Feature {feature_id} not found for background geocoding")
                    return

                # Get the geojson data
                geojson = feature_store.geojson
                if not geojson:
                    logger.warning(f"Feature {feature_id} has no geojson data")
                    return

                # Determine the appropriate feature class
                feature_class = _get_feature_class_from_geojson(geojson)
                if not feature_class:
                    logger.warning(f"Feature {feature_id} has unsupported geometry type for geocoding")
                    return

                geojson_for_validation = geojson.copy()
                geojson_for_validation['properties']['geojson_hash'] = feature_store.geojson_hash
                feature_instance = feature_class(**geojson_for_validation)

                # Generate geocoding tags using the tag generator
                geocoding_generator = GeocodingTagGenerator()
                try:
                    geocoding_tags = geocoding_generator.process(feature_instance, import_log=None)
                except:
                    logger.warning(f"Geocoding tag generation failed for feature {feature_id}: {traceback.format_exc()}")
                    return

                geojson.setdefault('properties', {})
                geojson['properties']['system_tags'] = list(set(geojson['properties'].get('system_tags', []) + geocoding_tags))

                feature_store.geojson = geojson
                feature_store.save(update_fields=['geojson'])
        except:
            # Log error but don't raise - this is background processing
            logger.error(f"Error in background geocoding for feature {feature_id}: {traceback.format_exc()}")

    # Start the geocoding in a background thread
    thread = threading.Thread(target=_geocode_worker, daemon=True, name=f"GeocodeFeature-{feature_id}")
    thread.start()
    logger.debug(f"Started background geocoding thread for feature {feature_id}")
