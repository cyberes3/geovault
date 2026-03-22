"""Shared utilities for collections"""
from typing import List, Set

from django.db import connection
from django.db.models import Q

from api.models import Collection, FeatureStore


def get_distinct_tag_strings_on_user_features(user_id: int) -> Set[str]:
    """
    Distinct tag strings that appear on at least one of the user's features with geometry,
    in either properties.tags or properties.system_tags. Matches how collection tag rules
    resolve features in get_collection_feature_ids.
    """
    table_name = FeatureStore._meta.db_table
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT DISTINCT tag FROM (
                SELECT t.tag_value AS tag
                FROM {table_name} f
                CROSS JOIN LATERAL jsonb_array_elements_text(f.geojson->'properties'->'tags') AS t(tag_value)
                WHERE f.user_id = %s
                  AND f.geometry IS NOT NULL
                  AND f.geojson->'properties'->'tags' IS NOT NULL
                  AND jsonb_typeof(f.geojson->'properties'->'tags') = 'array'
                  AND t.tag_value <> ''
                UNION
                SELECT t.tag_value AS tag
                FROM {table_name} f
                CROSS JOIN LATERAL jsonb_array_elements_text(f.geojson->'properties'->'system_tags') AS t(tag_value)
                WHERE f.user_id = %s
                  AND f.geometry IS NOT NULL
                  AND f.geojson->'properties'->'system_tags' IS NOT NULL
                  AND jsonb_typeof(f.geojson->'properties'->'system_tags') = 'array'
                  AND t.tag_value <> ''
            ) AS combined_tags
            """,
            [user_id, user_id],
        )
        return {row[0] for row in cursor.fetchall()}


def filter_collection_tags_for_user(user, tags: List[str]) -> List[str]:
    """Keep only tags that still exist on the user's features; preserve request order."""
    if not tags:
        return []
    existing = get_distinct_tag_strings_on_user_features(user.id)
    return [t for t in tags if t in existing]


def filter_feature_ids_for_user(user, feature_ids: List[int]) -> List[int]:
    """Keep only feature IDs that exist for this user; preserve request order."""
    if not feature_ids:
        return []
    user_feature_ids = set(
        FeatureStore.objects.filter(user=user, id__in=feature_ids).values_list('id', flat=True)
    )
    return [fid for fid in feature_ids if fid in user_feature_ids]


def get_collection_feature_ids(collection: Collection) -> Set[int]:
    """
    Get the set of feature IDs that belong to a collection.
    This is the union of features matching tags and individually selected features.
    
    Args:
        collection: The Collection instance to get feature IDs for
        
    Returns:
        Set of feature IDs (integers) that match the collection criteria
    """
    feature_ids_set: Set[int] = set()

    # 1. Get features matching ANY of the collection's tags (OR logic)
    if collection.tags:
        base_query = FeatureStore.objects.filter(user=collection.user).exclude(geometry__isnull=True)

        tag_query = Q()
        for tag in collection.tags:
            if tag:  # Only process non-empty tags
                tag_query |= Q(geojson__properties__tags__contains=[tag]) | Q(geojson__properties__system_tags__contains=[tag])

        if tag_query:
            tag_features = base_query.filter(tag_query).values_list('id', flat=True)
            feature_ids_set.update(tag_features)

    # 2. Add individually selected features
    if collection.feature_ids:
        # Verify these features belong to the user
        user_feature_ids = set(
            FeatureStore.objects.filter(user=collection.user, id__in=collection.feature_ids)
            .values_list('id', flat=True)
        )
        feature_ids_set.update(user_feature_ids)

    return feature_ids_set


def _serialize_collection(collection: Collection, feature_count: int = None) -> dict:
    """
    Serialize a Collection instance to a dictionary representation.
    
    Args:
        collection: The Collection instance to serialize
        feature_count: Optional pre-computed feature count. If None, will be computed.
        
    Returns:
        Dictionary representation of the collection
    """
    if feature_count is None:
        feature_count = _count_collection_features(collection)

    return {
        'id': collection.id,
        'name': collection.name,
        'description': collection.description or '',
        'tags': collection.tags,
        'feature_ids': collection.feature_ids,
        'feature_count': feature_count,
        'created_at': collection.created_at.isoformat(),
        'updated_at': collection.updated_at.isoformat()
    }


def _count_collection_features(collection: Collection) -> int:
    """
    Count the number of features in a collection.
    This is the union of features matching tags and individually selected features.
    """
    return len(get_collection_feature_ids(collection))
