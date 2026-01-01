"""
CalTopo service wrapper for integrating with CalTopo API.

This module provides a high-level interface to the CalTopo API using the
caltopo_python library. It handles session management, feature conversion,
and import tracking.
"""
from typing import List, Dict, Any, Optional

from caltopo_python import CaltopoSession
from django.contrib.auth import get_user_model
from requests.exceptions import ReadTimeout, Timeout

from api.models import CalTopoUser
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.hooks import register_import_hook

_logger = get_tagged_logger('CalTopoService')


class CalTopoTimeoutError(Exception):
    """Raised when a CalTopo API request times out."""
    pass


def get_caltopo_session(user) -> Optional[CaltopoSession]:
    """
    Get or create a CaltopoSession for a user.
    
    Args:
        user: Django User object
        
    Returns:
        CaltopoSession instance or None if credentials not configured
    """
    try:
        caltopo_user = CalTopoUser.objects.get(user=user)
    except CalTopoUser.DoesNotExist:
        return None

    return CaltopoSession(
        domainAndPort='caltopo.com',
        id=caltopo_user.credential_id,
        key=caltopo_user.credential_key,
        accountId=caltopo_user.account_id,
        sync=False  # Don't use background sync for API calls
    )


def list_maps(user) -> List[Dict[str, Any]]:
    """
    Get list of all available maps for a user.
    
    Args:
        user: Django User object
        
    Returns:
        List of map dictionaries with id, title, updated, type, etc.
        
    Raises:
        CalTopoTimeoutError: If the CalTopo API request times out
    """
    session = get_caltopo_session(user)
    if not session:
        return []

    try:
        # Ensure account data is loaded before getting map list
        account_data = session.getAccountData()

        # Check if accountData has 'rels' key before including bookmarks
        # The library code assumes 'rels' exists when includeBookmarks=True
        include_bookmarks = 'rels' in account_data if account_data else False

        maps = session.getMapList(includeBookmarks=include_bookmarks, refresh=True)
        return maps if isinstance(maps, list) else []
    except (ReadTimeout, Timeout) as e:
        _logger.warning(f"CalTopo API timeout while listing maps for user {user.id}: {e}")
        raise CalTopoTimeoutError("CalTopo API request timed out") from e


def get_map_features(user, map_id: str) -> Optional[List[Dict[str, Any]]]:
    """
    Get all features from a specific CalTopo map.
    
    Args:
        user: Django User object
        map_id: CalTopo map ID
        
    Returns:
        List of feature dictionaries from CalTopo, or None if map not found/access denied
        
    Raises:
        CalTopoTimeoutError: If the CalTopo API request times out
    """
    session = get_caltopo_session(user)
    if not session:
        return None

    try:
        if not session.openMap(mapID=map_id):
            return None

        features = session.getFeatures(forceRefresh=True)

        if isinstance(features, dict) and 'state' in features:
            return features.get('state', {}).get('features', [])
        elif isinstance(features, list):
            return features
        else:
            return []
    except (ReadTimeout, Timeout) as e:
        _logger.warning(f"CalTopo API timeout while getting features for map {map_id} (user {user.id}): {e}")
        raise CalTopoTimeoutError("CalTopo API request timed out") from e


def get_feature(user, map_id: str, feature_id: str, feature_class: str) -> Optional[Dict[str, Any]]:
    """
    Get a single feature from a CalTopo map.
    
    Args:
        user: Django User object
        map_id: CalTopo map ID
        feature_id: CalTopo feature ID
        feature_class: CalTopo feature class (e.g., 'Marker', 'Line', 'Shape')
        
    Returns:
        Feature dictionary or None if not found
        
    Raises:
        CalTopoTimeoutError: If the CalTopo API request times out
    """
    session = get_caltopo_session(user)
    if not session:
        return None

    try:
        if not session.openMap(mapID=map_id):
            return None

        return session.getFeature(id=feature_id, featureClass=feature_class, forceRefresh=True)
    except (ReadTimeout, Timeout) as e:
        _logger.warning(f"CalTopo API timeout while getting feature {feature_id} from map {map_id} (user {user.id}): {e}")
        raise CalTopoTimeoutError("CalTopo API request timed out") from e


def convert_caltopo_to_geojson(caltopo_feature: Dict[str, Any], map_id: str = None) -> Dict[str, Any]:
    """
    Convert a CalTopo feature to GeoJSON format.
    
    Args:
        caltopo_feature: Feature dictionary from CalTopo API
        map_id: Optional CalTopo map ID to store in properties
        
    Returns:
        GeoJSON Feature dictionary
    """
    if not caltopo_feature:
        return None

    # Extract geometry
    geometry = caltopo_feature.get('geometry', {})
    if not geometry:
        _logger.warning(f"CalTopo feature has no geometry: {caltopo_feature.get('id')}")
        return None

    # Extract properties
    caltopo_props = caltopo_feature.get('properties', {})

    # Build GeoJSON properties
    geojson_properties = {
        'name': caltopo_props.get('title', ''),
        'description': caltopo_props.get('description', ''),
    }

    # Preserve styling if present
    if 'stroke' in caltopo_props:
        geojson_properties['stroke'] = caltopo_props['stroke']
    if 'stroke-width' in caltopo_props:
        geojson_properties['stroke-width'] = caltopo_props['stroke-width']
    if 'fill' in caltopo_props:
        geojson_properties['fill'] = caltopo_props['fill']
    if 'fill-opacity' in caltopo_props:
        geojson_properties['fill-opacity'] = caltopo_props['fill-opacity']
    if 'icon' in caltopo_props:
        geojson_properties['icon'] = caltopo_props['icon']

    # Store CalTopo metadata for tracking
    if map_id:
        geojson_properties['caltopo_map_id'] = map_id
    if 'id' in caltopo_feature:
        geojson_properties['caltopo_feature_id'] = caltopo_feature['id']
    if 'class' in caltopo_props:
        geojson_properties['caltopo_feature_class'] = caltopo_props['class']

    # Build GeoJSON feature
    geojson_feature = {
        'type': 'Feature',
        'geometry': geometry,
        'properties': geojson_properties
    }

    return geojson_feature


def _caltopo_import_hook(import_item, user_id, created_features):
    """Hook to update CalTopo imported_features mapping after import completes."""
    if not created_features:
        return

    # Filter features with CalTopo metadata
    caltopo_features = [
        f for f in created_features
        if f.geojson.get('properties', {}).get('caltopo_map_id')
    ]

    if not caltopo_features:
        return

    # Get or create CalTopoUser record
    User = get_user_model()
    try:
        user = User.objects.get(id=user_id)
        caltopo_user, _ = CalTopoUser.objects.get_or_create(user=user)
    except User.DoesNotExist:
        return

    # Group features by map_id for efficient updates
    features_by_map = {}
    for feature in caltopo_features:
        props = feature.geojson.get('properties', {})
        map_id = props.get('caltopo_map_id')
        feature_id = props.get('caltopo_feature_id')

        if map_id and feature_id:
            if map_id not in features_by_map:
                features_by_map[map_id] = {}
            features_by_map[map_id][feature_id] = feature.id

    # Update imported_features mapping
    if features_by_map:
        if not caltopo_user.imported_features:
            caltopo_user.imported_features = {}

        for map_id, feature_mapping in features_by_map.items():
            if map_id not in caltopo_user.imported_features:
                caltopo_user.imported_features[map_id] = {}
            caltopo_user.imported_features[map_id].update(feature_mapping)

        caltopo_user.save()


register_import_hook('caltopo', _caltopo_import_hook)
