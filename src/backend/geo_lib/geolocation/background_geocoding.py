"""
Background geocoding service for features.

This module provides asynchronous geocoding functionality that allows
features to be saved immediately while geocoding tags are generated in
the background.
"""
import threading
import traceback
from typing import Optional

from django.db import transaction

from api.models import FeatureStore
from geo_lib.processing.tagging.modules.geocoding import GeocodingTagGenerator
from geo_lib.types.feature import (
    PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature
)
from geo_lib.logging.console import get_job_logger

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
                
                # Create feature instance for tag generation
                try:
                    feature_instance = feature_class(**geojson)
                except Exception as e:
                    logger.warning(f"Failed to create feature instance for {feature_id}: {e}")
                    return
                
                # Generate geocoding tags using the tag generator
                geocoding_generator = GeocodingTagGenerator()
                try:
                    geocoding_tags = geocoding_generator.process(feature_instance, import_log=None)
                except Exception as e:
                    logger.warning(f"Geocoding tag generation failed for feature {feature_id}: {e}")
                    return
                
                # Update system_tags in geojson
                if 'properties' not in geojson:
                    geojson['properties'] = {}
                
                # Get existing system_tags
                existing_system_tags = geojson['properties'].get('system_tags', [])
                if not isinstance(existing_system_tags, list):
                    existing_system_tags = []
                
                # Remove any existing geocoding tags (to avoid duplicates)
                # We identify geocoding tags by checking if they're in the geocoding generator's tag_names
                # However, since geocoding tags are location-based (city, state, etc.), we'll just
                # add the new tags and let the system handle deduplication if needed
                # Actually, we should merge intelligently - add new geocoding tags without duplicates
                existing_system_tags_set = set(existing_system_tags)
                new_geocoding_tags = [tag for tag in geocoding_tags if tag not in existing_system_tags_set]
                
                if new_geocoding_tags:
                    # Merge new geocoding tags with existing system tags
                    updated_system_tags = existing_system_tags + new_geocoding_tags
                    geojson['properties']['system_tags'] = updated_system_tags
                    
                    # Update the feature in the database
                    feature_store.geojson = geojson
                    feature_store.save(update_fields=['geojson'])
                    
                    logger.info(f"Added {len(new_geocoding_tags)} geocoding tag(s) to feature {feature_id}")
                else:
                    logger.debug(f"No new geocoding tags for feature {feature_id}")
        
        except Exception as e:
            # Log error but don't raise - this is background processing
            logger.error(f"Error in background geocoding for feature {feature_id}: {traceback.format_exc()}")
    
    # Start the geocoding in a background thread
    thread = threading.Thread(target=_geocode_worker, daemon=True, name=f"GeocodeFeature-{feature_id}")
    thread.start()
    logger.debug(f"Started background geocoding thread for feature {feature_id}")

