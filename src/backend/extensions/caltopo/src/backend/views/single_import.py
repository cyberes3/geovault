"""
CalTopo single feature import endpoint.
"""
from datetime import datetime, timezone
from typing import Dict, Any, ClassVar

from django.db import transaction
from django.http import HttpRequest, JsonResponse
from django.views.decorators.http import require_http_methods
from pydantic import BaseModel, Field, ConfigDict, field_validator

from api.services.feature_service import FeatureService
from api.utils.responses import error_response, success_response
from website.extensions.import_provider import (
    EXTERNAL_IDS_FEATURE_KEY,
    ImportHookPayload,
    collect_external_ids,
)
from api.validation.decorators import validate_payload
from extensions.caltopo.src.backend.import_provider import CaltopoImportProvider
from extensions.caltopo.src.backend.services.caltopo_api import get_feature, convert_caltopo_to_geojson
from extensions.caltopo.src.backend.utils.caltopo_helpers import require_caltopo_connection, perform_caltopo_call, VALID_CALTOPO_FEATURE_CLASSES
from extensions.caltopo.src.backend.utils.rate_limit import caltopo_rate_limited
from geo_lib.duplicates.adapters.import_draft import build_duplicate_index
from geo_lib.duplicates.detector import DuplicateDetector
from geo_lib.duplicates.verdict import VerdictKind, VerdictScope
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.reverse_geocoding.background_geocoding import reverse_geocode_feature_async
from geo_lib.processing.logging import ImportLog
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.tags.tag_writer import SystemTagWriter
from geo_lib.types.validation import match_geometry_class
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from geo_lib.validation.geometry_validation import GeometryValidationError
from website.auth_decorators import api_or_login_required_401


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
@caltopo_rate_limited
def import_caltopo_feature(request: HttpRequest, validated_data: Dict[str, Any]) -> JsonResponse:
    """
    Import a single feature from CalTopo.
    
    POST /api/extensions/caltopo/import/feature/
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
    caltopo_feature, error_resp = perform_caltopo_call(
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

    warnings = []
    geojson_hash = generate_geojson_hash(geojson_feature)
    geojson_feature['properties']['geojson_hash'] = geojson_hash
    duplicate_index = build_duplicate_index(
        request.user.id,
        [geojson_feature],
        exclude_queue_id=0,
        uploaded_at=datetime.now(timezone.utc),
    )
    verdict = DuplicateDetector().detect_two_pass([geojson_feature], duplicate_index).get(geojson_hash)
    if verdict and verdict.kind != VerdictKind.NONE:
        match = verdict.matches[0] if verdict.matches else None
        warnings.append({
            'type': verdict.kind.value,
            'message': (
                'Feature with identical hash already exists'
                if verdict.kind == VerdictKind.HASH else
                'Feature with similar geometry already exists'
            ),
            'existing_features': [{
                'id': match.feature_store_id if match else None,
                'name': match.name if match else 'Unnamed',
            }],
            'scope': verdict.scope.value if verdict.scope != VerdictScope.NONE else None,
        })

    external_ids = collect_external_ids(geojson_feature)
    if external_ids.is_empty():
        external_ids = CaltopoImportProvider().extract_external_ids(geojson_feature)

    # Validate and normalize
    try:
        normalized_feature = validate_and_normalize_geojson_feature(
            geojson_feature,
            preserve_system_tags=None,
            preserve_geojson_hash=False
        )
    except GeometryValidationError as e:
        return error_response(f'Feature validation failed: {str(e)}', code=400)

    # Generate hash after normalization
    geojson_hash = generate_geojson_hash(normalized_feature)

    # Add geojson_hash to properties for geometry_type validation (required by Pydantic models)
    if 'properties' not in normalized_feature:
        normalized_feature['properties'] = {}
    normalized_feature['properties']['geojson_hash'] = geojson_hash

    # Generate system tags
    geometry_type = match_geometry_class(normalized_feature['geometry']['type'])
    feature_instance = geometry_type(**normalized_feature)
    system_tags = generate_auto_tags(feature_instance, import_log=ImportLog(), filename='caltopo-import', skip_reverse_geocoding=True)

    # Add system tags to properties
    normalized_feature['properties']['system_tags'] = system_tags

    # Remove geojson_hash from properties (it's stored separately in FeatureStore)
    del normalized_feature['properties']['geojson_hash']

    with transaction.atomic():
        if map_id in caltopo_user.imported_features and feature_id in caltopo_user.imported_features[map_id]:
            existing_feature_id = caltopo_user.imported_features[map_id][feature_id]
            FeatureService.delete(request.user, existing_feature_id)
            caltopo_user.imported_features[map_id].pop(feature_id, None)

        feature_store = FeatureService.create(
            request.user,
            normalized_feature,
            geojson_hash=geojson_hash,
        )
        SystemTagWriter.write_creation_tags(feature_store, system_tags)
        setattr(feature_store, EXTERNAL_IDS_FEATURE_KEY, external_ids)
        CaltopoImportProvider().on_import_finalized(ImportHookPayload(
            import_item=None,
            user_id=request.user.id,
            created_features=[feature_store],
            external_ids=[external_ids],
        ))

    reverse_geocode_feature_async(feature_store.id)
    normalized_feature['properties']['database_id'] = feature_store.id

    response_data = {'feature': normalized_feature, 'imported': True}
    if warnings:
        response_data['warnings'] = warnings

    return success_response(response_data, status=201)
