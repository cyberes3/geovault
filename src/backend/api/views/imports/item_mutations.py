"""Import-queue item update, delete, and featurestore import."""
import copy

from django.db import transaction
from django.views.decorators.http import require_http_methods

from api.models import ImportDraftFeature, ImportQueue
from api.services.feature_service import FeatureService
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, handle_404, server_error_response, success_response
from api.validation.decorators import validate_payload
from api.validation.payloads.features import FeatureUpdatePayload
from api.validation.payloads.imports import ImportToFeaturestorePayload
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.importing.geometry import feature_to_geos, spatial_sort_tuple
from geo_lib.importing.jobs.user_lock import is_user_import_lock_held
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.delete_job import DeleteJob
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.processing.jobs.import_job import ImportJob
from geo_lib.tags.protected import TagValidationError
from geo_lib.tags.tag_set import TagSet
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from website.auth_decorators import api_or_login_required_401

_logger = get_tagged_logger()

delete_job = DeleteJob(status_tracker)
import_job = ImportJob(status_tracker)


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
def delete_import_item(request, id):
    queue = get_object_or_404_for_user(ImportQueue, request.user, id=id)

    job_id = delete_job.start_delete_job(id, request.user.id, queue.original_filename)

    if job_id:
        return success_response({
            'msg': 'Delete job started',
            'job_id': job_id
        })
    return server_error_response('Failed to start delete job')


@api_or_login_required_401()
@require_http_methods(["PUT", "PATCH"])
@handle_404
@validate_payload(FeatureUpdatePayload)
def update_import_item(request, item_id, validated_data):
    queue = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    if queue.imported:
        return error_response(
            'Cannot update items that have already been imported to the feature store',
            code=400
        )
    if is_user_import_lock_held(request.user.id):
        return error_response('Another import job is already processing for this user', code=409)

    updates_by_id = {}
    features_to_update = validated_data['features']
    allowed_fields = {'name', 'description', 'created', 'tags', 'icon', 'marker-color', 'stroke', 'fill', 'fill-opacity'}
    alias_map = {'marker_color': 'marker-color', 'fill_opacity': 'fill-opacity'}

    for feature in features_to_update:
        properties = feature.get('properties', {})
        if not properties:
            continue
        feature_id = properties.get('geojson_hash')
        if not feature_id:
            _logger.warning(f"Skipping feature without geojson_hash: {properties.get('name', 'Unnamed')}")
            continue
        update_fields = {}
        for field, value in properties.items():
            mapped = alias_map.get(field, field)
            if mapped in allowed_fields:
                update_fields[mapped] = value
        if not update_fields:
            _logger.warning(f"Skipping feature {feature_id}: no updatable fields provided")
            continue
        updates_by_id[feature_id] = update_fields

    updated_count = 0
    with transaction.atomic():
        drafts = list(ImportDraftFeature.objects.select_for_update().filter(
            queue=queue, geojson_hash__in=list(updates_by_id.keys())
        ))
        for draft in drafts:
            update_fields = updates_by_id.get(draft.geojson_hash)
            if not update_fields:
                continue
            merged_feature = copy.deepcopy(draft.geojson)
            original_system_tags = FeatureService.extract_system_tags(merged_feature)
            merged_feature.setdefault('properties', {})
            skip_draft = False
            for field, value in update_fields.items():
                if field == 'tags':
                    try:
                        user_tags = list(TagSet.normalize_user_tags(value))
                    except TagValidationError as exc:
                        _logger.warning(f"Skipping feature {draft.geojson_hash}: {exc.message}")
                        skip_draft = True
                        break
                    merged_feature['properties']['tags'] = user_tags
                else:
                    merged_feature['properties'][field] = value
            if skip_draft:
                continue
            normalized_feature = validate_and_normalize_geojson_feature(
                merged_feature,
                preserve_system_tags=original_system_tags,
                preserve_geojson_hash=False,
            )
            digest = generate_geojson_hash(normalized_feature)
            normalized_feature.setdefault('properties', {})['geojson_hash'] = digest
            sort_key = spatial_sort_tuple(normalized_feature)
            draft.geojson = normalized_feature
            draft.geojson_hash = digest
            draft.name = (normalized_feature.get('properties') or {}).get('name') or ''
            draft.sort_lat = -sort_key[0]
            draft.sort_lon = sort_key[1]
            draft.geometry = feature_to_geos(normalized_feature) or draft.geometry
            draft.save()
            updated_count += 1

    return success_response({
        'msg': f'Successfully updated {updated_count} feature(s)',
        'updated_count': updated_count
    })


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@validate_payload(ImportToFeaturestorePayload, allow_empty=True)
def import_to_featurestore(request, item_id, validated_data):
    import_item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    if import_item.imported or import_item.queue_status == ImportQueue.STATUS_IMPORTED:
        return error_response(
            'This item has already been imported to the feature store',
            code=400
        )
    if import_item.queue_status in (
        ImportQueue.STATUS_CANCELED,
        ImportQueue.STATUS_FAILED,
        ImportQueue.STATUS_PROCESSING,
    ):
        return error_response(
            f'Cannot import an item that is {import_item.queue_status}',
            code=400
        )

    import_custom_icons = validated_data.get('import_custom_icons', True)
    skipped = validated_data.get('skipped', [])
    restored = validated_data.get('restored', [])

    if import_item.file_hash:
        earlier_duplicates = ImportQueue.objects.filter(
            user=request.user,
            file_hash=import_item.file_hash,
            imported=False,
            timestamp__lt=import_item.timestamp
        ).order_by('timestamp').first()

        if earlier_duplicates:
            return error_response(
                f'This file is a duplicate of "{earlier_duplicates.original_filename}" which is already in the import queue',
                code=409
            )

    job_id = import_job.start_import_job(
        item_id=item_id,
        user_id=request.user.id,
        import_custom_icons=import_custom_icons,
        skipped=skipped,
        restored=restored,
    )

    return success_response({
        'msg': 'Import job started',
        'job_id': job_id,
        'item_id': item_id
    })
