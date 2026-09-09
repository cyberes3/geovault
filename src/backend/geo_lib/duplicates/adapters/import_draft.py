from datetime import datetime

from api.models import ImportDraftFeature, ImportQueue
from geo_lib.duplicates.geometry_join import GeometryJoin, geos_from_geometry, gist_join_import_drafts
from geo_lib.duplicates.hash_index import HashIndex
from geo_lib.duplicates.identity import FeatureRef
from geo_lib.duplicates.index import DuplicateIndex
from geo_lib.duplicates.adapters.feature_store import build_library_geometry_join, build_library_hash_index


def build_draft_hash_index(
    user_id: int,
    exclude_queue_id: int,
    uploaded_at: datetime,
) -> HashIndex:
    index = HashIndex()
    rows = (
        ImportDraftFeature.objects.filter(
            user_id=user_id,
            geojson_hash__isnull=False,
            queue__imported=False,
            queue__replacement__isnull=True,
            queue__queue_status__in=(ImportQueue.STATUS_PROCESSING, ImportQueue.STATUS_READY),
        )
        .exclude(queue_id=exclude_queue_id)
        .filter(queue__timestamp__lt=uploaded_at)
        .order_by('queue__timestamp', 'queue_id', 'id')
        .values_list('geojson_hash', 'queue_id', 'spatial_index', 'name', 'geometry_type', 'queue__id')
    )
    equal_ts_rows = (
        ImportDraftFeature.objects.filter(
            user_id=user_id,
            geojson_hash__isnull=False,
            queue__imported=False,
            queue__replacement__isnull=True,
            queue__queue_status__in=(ImportQueue.STATUS_PROCESSING, ImportQueue.STATUS_READY),
            queue__timestamp=uploaded_at,
            queue_id__lt=exclude_queue_id,
        )
        .order_by('queue_id', 'id')
        .values_list('geojson_hash', 'queue_id', 'spatial_index', 'name', 'geometry_type', 'queue__id')
    )
    for geojson_hash, queue_id, spatial_index, name, geometry_type, _qid in list(rows) + list(equal_ts_rows):
        if not geojson_hash:
            continue
        index.add(
            geojson_hash,
            FeatureRef(
                draft_queue_id=queue_id,
                spatial_index=spatial_index,
                name=name or '',
                geometry_type=geometry_type or '',
            ),
        )
    return index


def build_draft_geometry_join(
    user_id: int,
    features: list[dict],
    exclude_queue_id: int,
    uploaded_at: datetime,
) -> GeometryJoin:
    inputs = []
    for input_id, feature in enumerate(features):
        geos = geos_from_geometry((feature or {}).get('geometry'))
        if geos is not None:
            inputs.append((input_id, geos))
    return gist_join_import_drafts(
        inputs,
        user_id,
        exclude_queue_id,
        (uploaded_at, exclude_queue_id),
    )


def build_duplicate_index(
    user_id: int,
    features: list[dict],
    exclude_queue_id: int,
    uploaded_at: datetime,
) -> DuplicateIndex:
    return DuplicateIndex(
        library_hashes=build_library_hash_index(user_id),
        library_geometry=build_library_geometry_join(user_id, features),
        draft_hashes=build_draft_hash_index(user_id, exclude_queue_id, uploaded_at),
        draft_geometry=build_draft_geometry_join(user_id, features, exclude_queue_id, uploaded_at),
    )
