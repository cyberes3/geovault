import json
import traceback
from typing import Set

from django.db.models import Q
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.models import Collection, FeatureStore
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags
from geo_lib.logging.console import get_access_logger
from geo_lib.website.auth import login_required_401

logger = get_access_logger()


@login_required_401
@require_http_methods(["GET"])
def list_collections(request):
    """
    List all collections for the current user.
    """
    try:
        collections = Collection.objects.filter(user=request.user).order_by('-created_at')
        
        collections_data = []
        for collection in collections:
            # Count features in collection
            feature_count = _count_collection_features(collection)
            
            collections_data.append({
                'id': collection.id,
                'name': collection.name,
                'description': collection.description or '',
                'tags': collection.tags,
                'feature_ids': collection.feature_ids,
                'feature_count': feature_count,
                'created_at': collection.created_at.isoformat(),
                'updated_at': collection.updated_at.isoformat()
            })
        
        return JsonResponse({
            'success': True,
            'collections': collections_data
        })
    
    except Exception:
        logger.error(f"Error listing collections: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to list collections',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["POST"])
def create_collection(request):
    """
    Create a new collection.
    
    POST body:
    - name: string (required)
    - description: string (optional)
    - tags: array of strings (optional)
    - feature_ids: array of integers (optional)
    """
    try:
        data = json.loads(request.body)
        name = data.get('name', '').strip()
        description = data.get('description')
        if description is not None:
            description = description.strip() if description else None
        else:
            description = None
        tags = data.get('tags', [])
        feature_ids = data.get('feature_ids', [])
        
        if not name:
            return JsonResponse({
                'success': False,
                'error': 'name is required',
                'code': 400
            }, status=400)
        
        # Validate tags is a list
        if not isinstance(tags, list):
            tags = []
        
        # Validate feature_ids is a list of integers
        if not isinstance(feature_ids, list):
            feature_ids = []
        else:
            # Convert to integers and filter out invalid values
            try:
                feature_ids = [int(fid) for fid in feature_ids if fid is not None]
            except (ValueError, TypeError):
                feature_ids = []
        
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
        
        feature_count = _count_collection_features(collection)
        
        return JsonResponse({
            'success': True,
            'collection': {
                'id': collection.id,
                'name': collection.name,
                'description': collection.description or '',
                'tags': collection.tags,
                'feature_ids': collection.feature_ids,
                'feature_count': feature_count,
                'created_at': collection.created_at.isoformat(),
                'updated_at': collection.updated_at.isoformat()
            }
        }, status=201)
    
    except json.JSONDecodeError:
        return JsonResponse({
            'success': False,
            'error': 'Invalid JSON in request body',
            'code': 400
        }, status=400)
    except Exception:
        logger.error(f"Error creating collection: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to create collection',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["GET"])
def get_collection(request, collection_id):
    """
    Get a single collection by ID.
    """
    try:
        collection = Collection.objects.get(id=collection_id, user=request.user)
        
        feature_count = _count_collection_features(collection)
        
        return JsonResponse({
            'success': True,
            'collection': {
                'id': collection.id,
                'name': collection.name,
                'description': collection.description or '',
                'tags': collection.tags,
                'feature_ids': collection.feature_ids,
                'feature_count': feature_count,
                'created_at': collection.created_at.isoformat(),
                'updated_at': collection.updated_at.isoformat()
            }
        })
    
    except Collection.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Collection not found',
            'code': 404
        }, status=404)
    except Exception:
        logger.error(f"Error getting collection: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get collection',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["PUT", "PATCH"])
def update_collection(request, collection_id):
    """
    Update a collection.
    
    PUT/PATCH body:
    - name: string (optional)
    - description: string (optional)
    - tags: array of strings (optional)
    - feature_ids: array of integers (optional)
    """
    try:
        collection = Collection.objects.get(id=collection_id, user=request.user)
        
        data = json.loads(request.body)
        
        # Update name if provided
        if 'name' in data:
            name = data.get('name', '').strip()
            if name:
                collection.name = name
            else:
                return JsonResponse({
                    'success': False,
                    'error': 'name cannot be empty',
                    'code': 400
                }, status=400)
        
        # Update description if provided
        if 'description' in data:
            description = data.get('description')
            if description is not None:
                description = description.strip() if description else None
            else:
                description = None
            collection.description = description
        
        # Update tags if provided
        if 'tags' in data:
            tags = data.get('tags', [])
            if isinstance(tags, list):
                collection.tags = tags
            else:
                collection.tags = []
        
        # Update feature_ids if provided
        if 'feature_ids' in data:
            feature_ids = data.get('feature_ids', [])
            if isinstance(feature_ids, list):
                # Convert to integers and filter out invalid values
                try:
                    feature_ids = [int(fid) for fid in feature_ids if fid is not None]
                except (ValueError, TypeError):
                    feature_ids = []
                
                # Verify that all feature_ids belong to the user
                if feature_ids:
                    user_feature_ids = set(
                        FeatureStore.objects.filter(user=request.user, id__in=feature_ids)
                        .values_list('id', flat=True)
                    )
                    feature_ids = [fid for fid in feature_ids if fid in user_feature_ids]
                
                collection.feature_ids = feature_ids
            else:
                collection.feature_ids = []
        
        collection.save()
        
        feature_count = _count_collection_features(collection)
        
        return JsonResponse({
            'success': True,
            'collection': {
                'id': collection.id,
                'name': collection.name,
                'description': collection.description or '',
                'tags': collection.tags,
                'feature_ids': collection.feature_ids,
                'feature_count': feature_count,
                'created_at': collection.created_at.isoformat(),
                'updated_at': collection.updated_at.isoformat()
            }
        })
    
    except Collection.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Collection not found',
            'code': 404
        }, status=404)
    except json.JSONDecodeError:
        return JsonResponse({
            'success': False,
            'error': 'Invalid JSON in request body',
            'code': 400
        }, status=400)
    except Exception:
        logger.error(f"Error updating collection: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to update collection',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["DELETE"])
def delete_collection(request, collection_id):
    """
    Delete a collection.
    """
    try:
        collection = Collection.objects.get(id=collection_id, user=request.user)
        collection.delete()
        
        return JsonResponse({
            'success': True,
            'message': 'Collection deleted successfully'
        })
    
    except Collection.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Collection not found',
            'code': 404
        }, status=404)
    except Exception:
        logger.error(f"Error deleting collection: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to delete collection',
            'code': 500
        }, status=500)


@login_required_401
@require_http_methods(["GET"])
def get_collection_features(request, collection_id):
    """
    Get all features in a collection.
    Returns the union of:
    1. Features matching ANY of the collection's tags (OR logic)
    2. Individually selected features by ID
    
    Returns GeoJSON FeatureCollection format.
    """
    try:
        collection = Collection.objects.get(id=collection_id, user=request.user)
        
        # Get all feature IDs that match the collection criteria
        feature_ids_set: Set[int] = set()
        
        # 1. Get features matching ANY of the collection's tags (OR logic)
        if collection.tags:
            base_query = FeatureStore.objects.filter(user=request.user).exclude(geometry__isnull=True)
            
            # Build OR query for tags
            tag_query = Q()
            for tag in collection.tags:
                if tag:  # Only process non-empty tags
                    tag_query |= Q(geojson__properties__tags__contains=[tag])
            
            if tag_query:
                tag_features = base_query.filter(tag_query).values_list('id', flat=True)
                feature_ids_set.update(tag_features)
        
        # 2. Add individually selected features
        if collection.feature_ids:
            # Verify these features belong to the user
            user_feature_ids = set(
                FeatureStore.objects.filter(user=request.user, id__in=collection.feature_ids)
                .values_list('id', flat=True)
            )
            feature_ids_set.update(user_feature_ids)
        
        # Get all features by their IDs
        features = FeatureStore.objects.filter(id__in=feature_ids_set).exclude(geometry__isnull=True).order_by('id')
        
        # Convert to GeoJSON format
        geojson_features = []
        for feature in features:
            geojson_data = feature.geojson
            if geojson_data and 'geometry' in geojson_data:
                properties = geojson_data.get('properties', {}).copy()
                
                # Filter out protected tags from the tags list for display
                tags_list = properties.get('tags', [])
                if isinstance(tags_list, list):
                    filtered_tags = filter_protected_tags(tags_list, CONST_INTERNAL_TAGS)
                    properties['tags'] = filtered_tags
                
                # Include database ID in properties
                properties['_id'] = feature.id
                
                geojson_feature = {
                    "type": "Feature",
                    "geometry": geojson_data.get('geometry'),
                    "properties": properties,
                    "geojson_hash": feature.file_hash
                }
                geojson_features.append(geojson_feature)
        
        # Create GeoJSON FeatureCollection
        geojson_data = {
            "type": "FeatureCollection",
            "features": geojson_features
        }
        
        return JsonResponse({
            'success': True,
            'data': geojson_data,
            'feature_count': len(geojson_features)
        })
    
    except Collection.DoesNotExist:
        return JsonResponse({
            'success': False,
            'error': 'Collection not found',
            'code': 404
        }, status=404)
    except Exception:
        logger.error(f"Error getting collection features: {traceback.format_exc()}")
        return JsonResponse({
            'success': False,
            'error': 'Failed to get collection features',
            'code': 500
        }, status=500)


def _count_collection_features(collection: Collection) -> int:
    """
    Count the number of features in a collection.
    This is the union of features matching tags and individually selected features.
    """
    feature_ids_set: Set[int] = set()
    
    # Get features matching tags
    if collection.tags:
        base_query = FeatureStore.objects.filter(user=collection.user).exclude(geometry__isnull=True)
        
        tag_query = Q()
        for tag in collection.tags:
            if tag:
                tag_query |= Q(geojson__properties__tags__contains=[tag])
        
        if tag_query:
            tag_features = base_query.filter(tag_query).values_list('id', flat=True)
            feature_ids_set.update(tag_features)
    
    # Add individually selected features
    if collection.feature_ids:
        user_feature_ids = set(
            FeatureStore.objects.filter(user=collection.user, id__in=collection.feature_ids)
            .values_list('id', flat=True)
        )
        feature_ids_set.update(user_feature_ids)
    
    return len(feature_ids_set)

