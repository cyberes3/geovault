"""Collection CRUD operations"""
import traceback

from django.db import transaction
from django.views.decorators.http import require_http_methods

from api.models import Collection, FeatureStore
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import (
    success_response,
    server_error_response,
    handle_404,
)
from api.validation.feature_updates import validate_payload, CollectionCreatePayload, CollectionUpdatePayload
from api.views.collections._shared import _serialize_collection, _count_collection_features, _get_collection_feature_ids
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401

logger = get_tagged_logger('access')


@api_or_login_required_401()
@require_http_methods(["GET"])
def list_collections(request):
    """
    List all collections for the current user.
    Feature counts are pre-computed in batch to avoid N+1 queries.
    """
    try:
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
    
    except Exception:
        logger.error(f"Error listing collections: {traceback.format_exc()}")
        return server_error_response('Failed to list collections')


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
    try:
        name = validated_data['name'].strip()
        description = validated_data.get('description')
        if description is not None:
            description = description.strip() if description else None
        else:
            description = None
        tags = validated_data.get('tags', [])
        feature_ids = validated_data.get('feature_ids', [])
        
        # Verify that all feature_ids belong to the user
        if feature_ids:
            user_feature_ids = set(
                FeatureStore.objects.filter(user=request.user, id__in=feature_ids)
                .values_list('id', flat=True)
            )
            feature_ids = [fid for fid in feature_ids if fid in user_feature_ids]
        
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
    
    except Exception:
        logger.error(f"Error creating collection: {traceback.format_exc()}")
        return server_error_response('Failed to create collection')


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
            collection.tags = tags if tags else []
        
        # Update feature_ids if provided
        if 'feature_ids' in validated_data:
            feature_ids = validated_data['feature_ids']
            # Verify that all feature_ids belong to the user
            if feature_ids:
                user_feature_ids = set(
                    FeatureStore.objects.filter(user=request.user, id__in=feature_ids)
                    .values_list('id', flat=True)
                )
                feature_ids = [fid for fid in feature_ids if fid in user_feature_ids]
            collection.feature_ids = feature_ids
        
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
    feature_ids_set = _get_collection_feature_ids(collection)
    
    # Get all features by their IDs
    features = FeatureStore.objects.filter(id__in=feature_ids_set).exclude(geometry__isnull=True).order_by('id')
    
    # Convert to GeoJSON format
    geojson_features = []
    for feature in features:
        geojson_data = feature.geojson
        if geojson_data and 'geometry' in geojson_data:
            properties = geojson_data.get('properties', {}).copy()
            
            # Tags are already separated - user tags only in tags field
            # System tags are in system_tags field and not shown to user
            
            # Include database ID in properties
            properties['database_id'] = feature.id
            
            geojson_feature = {
                "type": "Feature",
                "geometry": geojson_data.get('geometry'),
                "properties": properties,
                "geojson_hash": feature.geojson_hash
            }
            geojson_features.append(geojson_feature)
    
    # Create GeoJSON FeatureCollection
    geojson_data = {
        "type": "FeatureCollection",
        "features": geojson_features
    }
    
    return success_response({
        'data': geojson_data,
        'feature_count': len(geojson_features)
    })
