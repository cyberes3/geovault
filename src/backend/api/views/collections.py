import json
import traceback
from typing import Set

from django.db.models import Q
from django.db import transaction
from django.http import JsonResponse, Http404
from django.views.decorators.http import require_http_methods

from api.models import Collection, FeatureStore
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import (
    error_response,
    success_response,
    not_found_response,
    server_error_response,
    handle_404,
)
from api.validation.feature_updates import validate_payload, CollectionCreatePayload, CollectionUpdatePayload
from api.views.feature_update import _apply_bulk_ops_and_save_feature
from geo_lib.feature_id import generate_geojson_hash
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.import_operations.styling import apply_bulk_operations as apply_bulk_operations_to_features
from geo_lib.processing.import_operations.validation import validate_bulk_operations_payload
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
            else:
                return error_response('name cannot be empty', code=400)
        
        # Update description if provided
        if 'description' in validated_data:
            description = validated_data.get('description')
            if description is not None:
                description = description.strip() if description else None
            else:
                description = None
            collection.description = description
        
        # Update tags if provided
        if 'tags' in validated_data:
            tags = validated_data.get('tags', [])
            collection.tags = tags
        
        # Update feature_ids if provided
        if 'feature_ids' in validated_data:
            feature_ids = validated_data.get('feature_ids', [])
            
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


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
def apply_bulk_operations_to_collection(request, collection_id):
    """
    Apply bulk operations to all features in a collection.

    This reuses the same bulk operations structure as the import process:
    {
      "bulk_operations": {
        "tags": [...],
        "pointColor": "#rrggbb" | null,
        "pointIcon": "url" | null,
        "lineColor": "#rrggbb" | null,
        "polyColor": "#rrggbb" | null
      }
    }
    """
    try:
        data = json.loads(request.body)
    except json.JSONDecodeError:
        return error_response('Invalid JSON in request body', code=400)

    if not isinstance(data, dict):
        return error_response('Request body must be a valid JSON object', code=400)

    bulk_ops = data.get("bulk_operations", {})
    is_valid, error_message = validate_bulk_operations_payload(bulk_ops)
    if not is_valid:
        return error_response(error_message, code=400)

    collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)

    # Build the same feature ID set used by get_collection_features/_count_collection_features
    feature_ids_set = _get_collection_feature_ids(collection)

    if not feature_ids_set:
        return success_response({
            "updated_count": 0,
            "msg": "No features found for this collection"
        })

    updated_count = 0

    # Iterate through all features and apply bulk operations using shared helper
    features_qs = FeatureStore.objects.filter(id__in=feature_ids_set).only("id", "geojson")

    # Import helper from feature_update module

    # Wrap in transaction to ensure atomicity
    with transaction.atomic():
        for feature in features_qs.iterator(chunk_size=200):
            if _apply_bulk_ops_and_save_feature(feature, bulk_ops):
                updated_count += 1

    return success_response({
        "updated_count": updated_count,
        "msg": f"Successfully updated {updated_count} feature(s) in collection"
    })


def _get_collection_feature_ids(collection: Collection) -> Set[int]:
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
    return len(_get_collection_feature_ids(collection))

