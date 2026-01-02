"""
CalTopo single feature import endpoint.
"""
import json
from typing import Dict, Any, ClassVar
from django.contrib.gis.geos import GEOSGeometry
from django.db import transaction
from django.http import HttpRequest, JsonResponse
from django.views.decorators.http import require_http_methods
from pydantic import BaseModel, Field, ConfigDict, field_validator

from api.models import CalTopoUser, FeatureStore
from api.utils.caltopo_constants import VALID_CALTOPO_FEATURE_CLASSES
from api.utils.rate_limit import caltopo_rate_limit
from api.utils.responses import error_response, success_response
from api.utils.caltopo_helpers import require_caltopo_connection, handle_caltopo_call
from api.validation.feature_updates import validate_payload
from api.views.features.updates.geometry import _normalize_geometry_coordinates
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.geocoding.background_geocoding import reverse_geocode_feature_async
from geo_lib.processing.duplicate_detection.find import _find_hash_duplicates, _find_geometry_duplicates
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.services.caltopo_service import get_feature, convert_caltopo_to_geojson
from geo_lib.types.validation import match_geometry_class
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError
from geo_lib.website.auth import api_or_login_required_401


class CalTopoSingleImportPayload(BaseModel):
    """Pydantic model for single feature import request."""
    model_config = ConfigDict(extra='forbid')

    # Valid CalTopo feature classes (shared constant)
    VALID_FEATURE_CLASSES: ClassVar[set[str]] = VALID_CALTOPO_FEATURE_CLASSES

    map_id: str = Field(description="CalTopo map ID")
    feature_id: str = Field(description="CalTopo feature ID")
    feature_class: str = Field(description="CalTopo feature class (e.g., 'Marker', 'Shape')")

    @field_validator('map_id')
    @classmethod
    def validate_map_id(cls, v: str) -> str:
        """Validate that map_id is a valid CalTopo map ID (3-7 characters, alphanumeric)."""
        if not v:
            raise ValueError("map_id cannot be empty")
        if len(v) < 3 or len(v) > 7:
            raise ValueError(f"map_id must be 3-7 characters long, got {len(v)} characters")
        if not v.isalnum():
            raise ValueError("map_id must contain only alphanumeric characters")
        return v

    @field_validator('feature_id')
    @classmethod
    def validate_feature_id(cls, v: str) -> str:
        """Validate that feature_id is a valid CalTopo feature ID."""
        if not v:
            raise ValueError("feature_id cannot be empty")
        if len(v) > 100:
            raise ValueError(f"feature_id must be 100 characters or less, got {len(v)} characters")
        return v

    @field_validator('feature_class')
    @classmethod
    def validate_feature_class(cls, v: str) -> str:
        """Validate that feature_class is a known CalTopo feature class."""
        if v not in cls.VALID_FEATURE_CLASSES:
            valid_classes = ', '.join(sorted(cls.VALID_FEATURE_CLASSES))
            raise ValueError(
                f"Invalid feature_class '{v}'. Must be one of: {valid_classes}"
            )
        return v


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(CalTopoSingleImportPayload)
@caltopo_rate_limit('import_feature')
def import_caltopo_feature(request: HttpRequest, validated_data: Dict[str, Any]) -> JsonResponse:
    """
    Import a single feature from CalTopo.
    
    POST /api/caltopo/import/feature/
    Body: {
        "map_id": "abc12",
        "feature_id": "1234567890",
        "feature_class": "Line"
    }
    """
    map_id = validated_data['map_id']
    feature_id = validated_data['feature_id']
    feature_class = validated_data['feature_class']
    
    caltopo_user, error_resp = require_caltopo_connection(request)
    if error_resp:
        return error_resp
    
    # Get feature from CalTopo
    caltopo_feature, error_resp = handle_caltopo_call(
        get_feature, request.user, map_id, feature_id, feature_class
    )
    if error_resp:
        return error_resp
    
    if not caltopo_feature:
        return error_response(f'Feature {feature_id} not found in map {map_id}', code=404)
    
    # Convert to GeoJSON
    geojson_feature = convert_caltopo_to_geojson(caltopo_feature, map_id=map_id)
    if not geojson_feature:
        # Log detailed error internally (convert_caltopo_to_geojson should log details)
        # Return generic error message to user (don't expose technical details)
        return error_response('Failed to process feature from CalTopo. The feature may be in an unsupported format.', code=500)
    
    # Check for duplicates (warning only)
    warnings = []
    geojson_hash = generate_geojson_hash(geojson_feature)
    geojson_feature['properties']['geojson_hash'] = geojson_hash
    
    hash_duplicates = _find_hash_duplicates([geojson_feature], request.user.id, source_filter='feature_store')
    if hash_duplicates:
        dup_info = hash_duplicates[0]
        warnings.append({
            'type': 'hash',
            'message': 'Feature with identical hash already exists',
            'existing_features': [
                {'id': ef.get('id'), 'name': ef.get('geojson', {}).get('properties', {}).get('name', 'Unnamed')}
                for ef in dup_info.get('existing_features', [])
            ]
        })
    
    _, geometry_duplicates, _ = _find_geometry_duplicates([geojson_feature], request.user.id, source_filter='feature_store')
    if geometry_duplicates:
        dup_info = geometry_duplicates[0]
        warnings.append({
            'type': 'geometry',
            'message': 'Feature with similar geometry already exists',
            'existing_features': [
                {'id': ef.get('id'), 'name': ef.get('geojson', {}).get('properties', {}).get('name', 'Unnamed')}
                for ef in dup_info.get('existing_features', [])
            ]
        })
    
    # Preserve CalTopo metadata before normalization (it will be stripped by whitelist)
    caltopo_metadata = {}
    if 'properties' in geojson_feature:
        props = geojson_feature['properties']
        if 'caltopo_map_id' in props:
            caltopo_metadata['caltopo_map_id'] = props['caltopo_map_id']
        if 'caltopo_feature_id' in props:
            caltopo_metadata['caltopo_feature_id'] = props['caltopo_feature_id']
        if 'caltopo_feature_class' in props:
            caltopo_metadata['caltopo_feature_class'] = props['caltopo_feature_class']
    
    # Validate and normalize
    try:
        normalized_feature = validate_and_normalize_geojson_feature(
            geojson_feature,
            preserve_system_tags=None,
            preserve_geojson_hash=False
        )
    except GeometryValidationError as e:
        return error_response(f'Feature validation failed: {str(e)}', code=400)
    
    # Restore CalTopo metadata after normalization
    if caltopo_metadata:
        if 'properties' not in normalized_feature:
            normalized_feature['properties'] = {}
        normalized_feature['properties'].update(caltopo_metadata)
    
    # Generate hash after normalization
    geojson_hash = generate_geojson_hash(normalized_feature)
    
    # Add geojson_hash to properties for geometry_type validation (required by Pydantic models)
    if 'properties' not in normalized_feature:
        normalized_feature['properties'] = {}
    normalized_feature['properties']['geojson_hash'] = geojson_hash
    
    # Generate system tags
    from geo_lib.processing.logging import ImportLog
    geometry_type = match_geometry_class(normalized_feature['geometry']['type'])
    feature_instance = geometry_type(**normalized_feature)
    system_tags = generate_auto_tags(feature_instance, import_log=ImportLog(), filename='caltopo-import', skip_reverse_geocoding=True)
    
    # Add system tags to properties
    normalized_feature['properties']['system_tags'] = system_tags
    
    # Remove geojson_hash from properties (it's stored separately in FeatureStore)
    del normalized_feature['properties']['geojson_hash']
    
    # Create geometry object from normalized GeoJSON (same approach as feature_processing.py)
    geometry = None
    if normalized_feature.get('geometry'):
        # Normalize coordinates to ensure all have Z dimension
        geom_data = _normalize_geometry_coordinates(normalized_feature['geometry'].copy())
        geometry = GEOSGeometry(json.dumps(geom_data))
    
    # Create FeatureStore entry
    with transaction.atomic():
        # Delete existing feature if re-importing
        # This handles cases where:
        # 1. Feature was previously imported and still exists (normal re-import)
        # 2. Feature was previously imported but user deleted it (clean up stale mapping)
        # 3. Feature was previously imported but user edited it (delete old version, import fresh)
        if map_id in caltopo_user.imported_features and feature_id in caltopo_user.imported_features[map_id]:
            existing_feature_id = caltopo_user.imported_features[map_id][feature_id]
            # Try to delete the feature (may not exist if user deleted it manually)
            FeatureStore.objects.filter(id=existing_feature_id, user=request.user).delete()
            # Always clean up the mapping, even if feature was already deleted
            caltopo_user.imported_features[map_id].pop(feature_id, None)
        
        feature_store = FeatureStore.objects.create(
            user=request.user,
            geojson=normalized_feature,
            geometry=geometry,
            geojson_hash=geojson_hash
        )
        
        if map_id not in caltopo_user.imported_features:
            caltopo_user.imported_features[map_id] = {}
        caltopo_user.imported_features[map_id][feature_id] = feature_store.id
        caltopo_user.save()
    
    reverse_geocode_feature_async(feature_store.id)
    normalized_feature['properties']['database_id'] = feature_store.id
    
    response_data = {'feature': normalized_feature, 'imported': True}
    if warnings:
        response_data['warnings'] = warnings
    
    return success_response(response_data, status=201)

