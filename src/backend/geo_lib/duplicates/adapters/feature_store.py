from django.db.models.fields.json import KeyTextTransform

from api.models import FeatureStore
from geo_lib.duplicates.geometry_join import GeometryJoin, geos_from_geometry, gist_join_feature_store
from geo_lib.duplicates.hash_index import HashIndex
from geo_lib.duplicates.identity import FeatureRef


def build_library_hash_index(user_id: int) -> HashIndex:
    index = HashIndex()
    rows = (
        FeatureStore.objects.owned_by(user_id)
        .main_map()
        .filter(geojson_hash__isnull=False)
        .order_by('timestamp', 'id')
        .values_list('geojson_hash', 'id')
    )
    for geojson_hash, store_id in rows:
        if not geojson_hash:
            continue
        index.add(geojson_hash, FeatureRef(feature_store_id=store_id))
    return index


def build_library_geometry_join(
    user_id: int,
    features: list[dict],
) -> GeometryJoin:
    inputs = []
    for input_id, feature in enumerate(features):
        geos = geos_from_geometry((feature or {}).get('geometry'))
        if geos is not None:
            inputs.append((input_id, geos))
    join = gist_join_feature_store(inputs, user_id)
    names = library_names(join.candidate_store_ids())
    if names:
        enriched: dict[int, list[FeatureRef]] = {}
        for input_id, refs in join.refs_by_input_id().items():
            enriched[input_id] = [
                FeatureRef(
                    feature_store_id=ref.feature_store_id,
                    name=names.get(ref.feature_store_id, ref.name),
                    geometry_type=ref.geometry_type,
                )
                for ref in refs
            ]
        join.replace_refs(enriched)
    return join


def library_names(store_ids: set[int]) -> dict[int, str]:
    if not store_ids:
        return {}
    rows = (
        FeatureStore.objects.filter(id__in=store_ids)
        .annotate(feature_name=KeyTextTransform('name', KeyTextTransform('properties', 'geojson')))
        .values_list('id', 'feature_name')
    )
    return {store_id: (name or '') for store_id, name in rows}
