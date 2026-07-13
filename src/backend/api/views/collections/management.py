"""Collection CRUD operations"""

from django.db import transaction
from django.views.decorators.http import require_http_methods

from api.models import Collection, FeatureStore
from api.services.feature_serialization import build_feature_collection_from_instances
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import (
    success_response,
    handle_404,
)
from api.validation.decorators import validate_payload
from api.validation.payloads.collections import CollectionCreatePayload, CollectionUpdatePayload
from api.views.collections.utils import (
    _count_collection_features,
    _serialize_collection,
    filter_collection_tags_for_user,
    filter_feature_ids_for_user,
    get_collection_feature_ids,
)
from website.auth_decorators import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["GET"])
def list_collections(request):
    """
    List all collections for the current user.
    Feature counts are pre-computed in batch to avoid N+1 queries.
    """
    collections = Collection.objects.filter(user=request.user).order_by('-created_at')

    # Pre-compute ALL feature counts before serialization to avoid N+1 query pattern
    # Without this, each _serialize_collection call would query the database separately
    collection_feature_counts = {
        collection.id: _count_collection_features(collection)
        for collection in collections
    }

    # Serialize with pre-computed counts
    collections_data = [
        _serialize_collection(
            collection,
            feature_count=collection_feature_counts[collection.id]
        )
        for collection in collections
    ]

    return success_response({
        'collections': collections_data
    })


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(CollectionCreatePayload)
def create_collection(request, validated_data):
    """
    Create a new collection.
    
    POST body:
    - name: string (required)
    - description: string (optional)
    - tags: array of strings (optional)
    - feature_ids: array of integers (optional)
    """
    name = validated_data['name'].strip()
    description = validated_data.get('description')
    if description is not None:
        description = description.strip() if description else None
    else:
        description = None
    tags = validated_data.get('tags', [])
    tags = filter_collection_tags_for_user(request.user, tags)
    feature_ids = filter_feature_ids_for_user(request.user, validated_data.get('feature_ids', []))

    # Create collection
    collection = Collection.objects.create(
        user=request.user,
        name=name,
        description=description,
        tags=tags,
        feature_ids=feature_ids
    )

    return success_response({
        'collection': _serialize_collection(collection)
    }, status=201)


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
def get_collection(request, collection_id):
    """
    Get a single collection by ID.
    """
    collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)
    return success_response({
        'collection': _serialize_collection(collection)
    })


@api_or_login_required_401()
@require_http_methods(["PUT", "PATCH"])
@validate_payload(CollectionUpdatePayload)
@handle_404
def update_collection(request, collection_id, validated_data):
    """
    Update a collection.
    
    PUT/PATCH body:
    - name: string (optional)
    - description: string (optional)
    - tags: array of strings (optional)
    - feature_ids: array of integers (optional)
    """
    collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)

    # Wrap all database modifications in a transaction
    with transaction.atomic():
        # Update name if provided
        if 'name' in validated_data:
            name = validated_data['name'].strip()
            if name:
                collection.name = name

        # Update description if provided
        if 'description' in validated_data:
            description = validated_data['description']
            if description is not None:
                collection.description = description.strip() if description else None
            else:
                collection.description = None

        # Update tags if provided
        if 'tags' in validated_data:
            tags = validated_data['tags']
            tags = filter_collection_tags_for_user(request.user, tags) if tags else []
            collection.tags = tags if tags else []

        # Update feature_ids if provided
        if 'feature_ids' in validated_data:
            collection.feature_ids = filter_feature_ids_for_user(
                request.user, validated_data['feature_ids']
            )

        collection.save()

    return success_response({
        'collection': _serialize_collection(collection)
    })


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
def delete_collection(request, collection_id):
    """
    Delete a collection.
    """
    collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)
    collection.delete()

    return success_response({'msg': 'Collection deleted successfully'})


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
def get_collection_features(request, collection_id):
    """
    Get all features in a collection.
    Returns the union of:
    1. Features matching ANY of the collection's tags (OR logic)
    2. Individually selected features by ID
    
    Returns GeoJSON FeatureCollection format.
    """
    collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)

    # Get all feature IDs that match the collection criteria
    feature_ids_set = get_collection_feature_ids(collection)

    # Get all features by their IDs. Collections can legitimately span any scope (a
    # user-defined grouping, not a map view), so no scope filter is applied here.
    features = FeatureStore.objects.filter(id__in=feature_ids_set).with_geometry().order_by('id')

    geojson_data = build_feature_collection_from_instances(features)

    return success_response({
        'data': geojson_data,
        'feature_count': len(geojson_data['features'])
    })
