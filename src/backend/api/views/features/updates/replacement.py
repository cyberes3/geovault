"""Replacement geometry apply endpoint."""
import traceback

from django.views.decorators.http import require_http_methods

from api.models import ImportDraftFeature, ImportQueue
from api.services.feature_service import FeatureService
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, handle_404, success_response
from api.validation.decorators import validate_payload
from api.validation.payloads.replacement import ReplacementGeometryPayload
from api.views.features.updates.feature_geometry import (
    get_feature_class_for_geometry_type,
    update_feature_geometry_field,
    validate_feature_with_class,
)
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.logging import ImportLog
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.tags.tag_writer import ProvenancePreserve, SystemTagWriter, TagWriter
from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.validation.geometry_validation import GeometryValidationError, normalize_and_validate_feature_update
from website.auth_decorators import api_or_login_required_401

_logger = get_tagged_logger()


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@validate_payload(ReplacementGeometryPayload)
def apply_replacement_geometry(request, feature_id, validated_data):
    """
    Apply replacement geometry from an ImportQueue entry to an existing feature.
    Only updates the geometry, preserving all properties (name, description, tags, styling, etc.).
    """
    feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)

    import_queue_id = validated_data['import_queue_id']
    feature_index = validated_data['feature_index']
    regenerate_tags = validated_data.get('regenerate_tags', False)

    import_queue = get_object_or_404_for_user(ImportQueue, request.user, id=import_queue_id)

    if import_queue.replacement != feature_id:
        return error_response('ImportQueue entry is not a replacement for this feature', 400)

    draft = ImportDraftFeature.objects.filter(queue=import_queue, spatial_index=feature_index).first()
    if draft is None:
        return error_response('ImportQueue entry has no draft feature at that index', 400)

    replacement_feature = draft.geojson
    if not isinstance(replacement_feature, dict) or 'geometry' not in replacement_feature:
        return error_response('Selected feature has invalid structure or missing geometry', 400)

    replacement_geometry = replacement_feature.get('geometry')
    if not replacement_geometry:
        return error_response('Selected feature has no geometry', 400)

    original_geojson = feature.geojson.copy()
    original_properties = original_geojson.get('properties', {})

    original_geometry_type = original_geojson.get('geometry', {}).get('type', '').lower()
    replacement_geometry_type = replacement_geometry.get('type', '').lower()

    if original_geometry_type != replacement_geometry_type:
        return error_response(
            f'Geometry type cannot change. Original: {original_geometry_type}, Replacement: {replacement_geometry_type}',
            400
        )

    updated_feature = {
        'type': 'Feature',
        'geometry': replacement_geometry,
        'properties': original_properties
    }

    updated_feature.setdefault('properties', {})['geojson_hash'] = generate_geojson_hash(updated_feature)

    try:
        feature_data = normalize_and_validate_feature_update(updated_feature, original_properties)
    except GeometryValidationError as e:
        return error_response(str(e), 400)

    is_valid, validated_data, error_msg = validate_feature_with_class(feature_data, feature_id)
    if not is_valid:
        return error_response(error_msg, 400)
    feature_data = validated_data

    try:
        feature_data = FeatureService.validate_and_preserve_feature(feature_data)
    except GeometryValidationError as e:
        return error_response(f'Feature validation failed: {str(e)}', 400)

    feature.geojson = feature_data
    feature.geojson_hash = generate_geojson_hash(feature_data)
    update_feature_geometry_field(feature, feature_data, feature_id)

    if regenerate_tags:
        try:
            geom_type = feature_data.get('geometry', {}).get('type', '').lower()
            tag_feature_class = get_feature_class_for_geometry_type(geom_type)
            if tag_feature_class is None:
                _logger.warning(f"Skipping tag regeneration for unsupported geometry type: {geom_type}")
            else:
                try:
                    feature_instance: GeoFeatureSupported = tag_feature_class(**feature_data)
                except Exception:
                    _logger.error(
                        "Error creating feature instance for tag regeneration %s:\n%s",
                        feature_id,
                        traceback.format_exc()
                    )
                else:
                    generated = generate_auto_tags(feature_instance, import_log=ImportLog())
                    feature.geojson = feature_data
                    new_system_tags = SystemTagWriter.apply_regenerated(
                        feature, generated, ProvenancePreserve()
                    )
                    feature_data.setdefault('properties', {})['system_tags'] = new_system_tags
                    try:
                        feature_data = FeatureService.validate_and_preserve_feature(feature_data)
                    except GeometryValidationError as e:
                        _logger.error(
                            f"Feature validation failed for feature {feature_id} during tag regeneration in replacement: {str(e)}"
                        )
                        feature_data = feature.geojson
                    feature.geojson = feature_data
        except Exception:
            _logger.error(f"Error regenerating tags for feature {feature_id}: {traceback.format_exc()}")

    feature.save()
    TagWriter.reindex_feature(feature)
    import_queue.delete()

    return success_response({
        'message': 'Replacement geometry applied successfully',
        'feature_id': feature.id
    })
