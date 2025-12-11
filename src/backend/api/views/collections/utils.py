"""Shared utilities for collections"""
from typing import Set

from django.db.models import Q

from api.models import Collection, FeatureStore


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
