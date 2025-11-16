import hashlib
import json
import logging
import traceback
from typing import List, Dict, Tuple, Any

from django import forms
from django.contrib.gis.geos import GEOSGeometry
from django.core.serializers.json import DjangoJSONEncoder
from django.http import HttpResponse, JsonResponse
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_http_methods

from api.models import ImportQueue, FeatureStore, DatabaseLogging
from geo_lib.feature_id import generate_feature_hash
from geo_lib.processing.jobs import upload_job, delete_job
from geo_lib.processing.status_tracker import status_tracker
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from geo_lib.processing.tagging import generate_auto_tags
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags, is_protected_tag
from geo_lib.security.file_validation import SecureFileValidator
from geo_lib.types.feature import PointFeature, PolygonFeature, LineStringFeature, MultiLineStringFeature
from geo_lib.website.auth import login_required_401

logger = logging.getLogger(__name__)


def strip_icon_properties(feature: dict) -> dict:
    """
    Remove icon-related properties from a feature.
    
    Args:
        feature: Feature dictionary with properties
        
    Returns:
        Feature dictionary with icon properties removed
    """
    if not isinstance(feature, dict) or 'properties' not in feature:
        return feature
    
    # Common property names that might contain icon hrefs
    icon_property_names = [
        'marker-symbol',
        'icon',
        'icon-href',
        'iconUrl',
        'icon_url',
        'marker-icon',
        'symbol',
        'styleUrl',  # KML style URLs might reference icons
    ]
    
    # Remove icon properties
    for prop_name in icon_property_names:
        if prop_name in feature['properties']:
            del feature['properties'][prop_name]
    
    # Also check nested structures (e.g., style objects)
    def remove_icons_from_dict(d):
        if not isinstance(d, dict):
            return
        for key, value in list(d.items()):
            if key in icon_property_names:
                del d[key]
            elif isinstance(value, dict):
                remove_icons_from_dict(value)
            elif isinstance(value, list):
                for item in value:
                    if isinstance(item, dict):
                        remove_icons_from_dict(item)
    
    remove_icons_from_dict(feature['properties'])
    
    return feature


def strip_duplicate_features(features) -> Tuple[List[Any], int, ImportLog]:
    """Remove 100% duplicate features and log the process."""
    import_log = ImportLog()

    if not features:
        return features, 0, import_log

    import_log.add(f"Checking {len(features)} features for internal duplicates", "Duplicate Detection", DatabaseLogLevel.INFO)

    # Track features by hash
    seen_hashes = set()
    unique_features = []
    duplicate_feature_count = 0

    for feature in features:
        # Generate hash for this feature
        feature_hash = generate_feature_hash(feature)

        if feature_hash in seen_hashes:
            # This is a duplicate
            duplicate_feature_count += 1
            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
            feature_type = feature.get('geometry', {}).get('type', 'Unknown')
            import_log.add(f"Duplicate within file: '{feature_name}' ({feature_type})", 'Duplicate Detection', DatabaseLogLevel.INFO)
        else:
            # This is a unique feature
            seen_hashes.add(feature_hash)
            unique_features.append(feature)

    if duplicate_feature_count > 0:
        import_log.add(f"Removed {duplicate_feature_count} duplicate features within the file", "Duplicate Detection", DatabaseLogLevel.INFO)
    else:
        import_log.add("No duplicate features found within the file", "Duplicate Detection", DatabaseLogLevel.INFO)

    return unique_features, duplicate_feature_count, import_log


def _normalize_coordinates(coords: List, tolerance: float = 1e-6) -> List:
    """Normalize coordinates by rounding to specified tolerance."""
    if isinstance(coords[0], (int, float)):
        # Single coordinate pair
        return [round(coord, 6) for coord in coords]
    else:
        # Nested coordinates (LineString or Polygon)
        return [_normalize_coordinates(coord, tolerance) for coord in coords]


def _coordinates_match(coord1: List, coord2: List, tolerance: float = 1e-6) -> bool:
    """Check if two coordinate sets match within tolerance."""
    norm1 = _normalize_coordinates(coord1, tolerance)
    norm2 = _normalize_coordinates(coord2, tolerance)
    return norm1 == norm2


def find_coordinate_duplicates(features: List[Dict], user_id: int) -> Tuple[List[Dict], List[Dict], ImportLog]:
    """
    Find features that have duplicate coordinates in the existing featurestore.
    Returns (unique_features, duplicate_features_with_originals, log_messages)
    """
    import_log = ImportLog()

    if not features:
        return features, [], import_log

    import_log.add(f"Checking {len(features)} features against existing features in your library", "Duplicate Detection", DatabaseLogLevel.INFO)

    # For large files, use batched approach to reduce database queries
    if len(features) > 1000:
        import_log.add("Using optimized batch processing for large file", "Duplicate Detection", DatabaseLogLevel.INFO)
        return _find_coordinate_duplicates_batched(features, user_id, import_log)

    # For smaller files, use the original approach
    unique_features = []
    duplicate_features = []

    for feature in features:
        geometry = feature.get('geometry', {})
        geom_type = geometry.get('type', '').lower()
        coordinates = geometry.get('coordinates', [])

        if not coordinates:
            # Skip features without coordinates
            unique_features.append(feature)
            continue

        # Check for existing features with matching coordinates
        existing_features = _find_existing_features_by_coordinates(coordinates, geom_type, user_id)

        if existing_features:
            # This is a duplicate - add original feature info
            duplicate_info = {
                'feature': feature,
                'existing_features': existing_features
            }
            duplicate_features.append(duplicate_info)

            # Create log message for the duplicate
            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
            feature_type = feature.get('geometry', {}).get('type', 'Unknown')
            existing_count = len(existing_features)
            import_log.add(f"Coordinate duplicate found: '{feature_name}' ({feature_type}) matches {existing_count} existing feature(s)", 'Duplicate Detection', DatabaseLogLevel.INFO)
        else:
            unique_features.append(feature)

    # Log summary
    if duplicate_features:
        import_log.add(f"Found {len(duplicate_features)} features that already exist in your library", "Duplicate Detection", DatabaseLogLevel.INFO)
    else:
        import_log.add("No duplicate features found in your existing library", "Duplicate Detection", DatabaseLogLevel.INFO)

    return unique_features, duplicate_features, import_log


def _find_coordinate_duplicates_batched(features: List[Dict], user_id: int, import_log: ImportLog) -> Tuple[List[Dict], List[Dict], ImportLog]:
    """
    Optimized duplicate detection for large files using batched database queries.
    """
    from django.contrib.gis.geos import GEOSGeometry
    from api.models import FeatureStore
    import json

    unique_features = []
    duplicate_features = []

    # Group features by geometry type for more efficient queries
    features_by_type = {'point': [], 'linestring': [], 'polygon': [], 'multilinestring': [], 'multipolygon': [], 'multipoint': [], 'geometrycollection': []}

    for i, feature in enumerate(features):
        geometry = feature.get('geometry', {})
        geom_type = geometry.get('type', '').lower()
        coordinates = geometry.get('coordinates', [])

        if not coordinates or geom_type not in features_by_type:
            unique_features.append(feature)
            continue

        features_by_type[geom_type].append((i, feature, coordinates))

    # Process each geometry type in batches
    for geom_type, type_features in features_by_type.items():
        if not type_features:
            continue

        import_log.add(f"Processing {len(type_features)} {geom_type} features for duplicates", 'Find Coordinate Duplicates')

        # Process in batches of 100 to avoid memory issues
        batch_size = 100
        for batch_start in range(0, len(type_features), batch_size):
            batch_end = min(batch_start + batch_size, len(type_features))
            batch_features = type_features[batch_start:batch_end]

            # Create geometries for this batch
            batch_geometries = []
            for idx, feature, coordinates in batch_features:
                try:
                    # Handle different geometry type naming conventions
                    if geom_type == 'multilinestring':
                        geom_type_name = 'MultiLineString'
                    elif geom_type == 'multipolygon':
                        geom_type_name = 'MultiPolygon'
                    elif geom_type == 'multipoint':
                        geom_type_name = 'MultiPoint'
                    elif geom_type == 'geometrycollection':
                        # GeometryCollection needs special handling - skip batching for now
                        # and use the regular duplicate detection logic
                        existing_features = _find_geometry_collection_duplicates(coordinates, user_id)
                        if existing_features:
                            duplicate_info = {
                                'feature': feature,
                                'existing_features': existing_features
                            }
                            duplicate_features.append(duplicate_info)
                            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                            existing_count = len(existing_features)
                            import_log.add(f"Coordinate duplicate found: '{feature_name}' (GeometryCollection) matches {existing_count} existing feature(s)", 'Duplicate Detection', DatabaseLogLevel.INFO)
                        else:
                            unique_features.append(feature)
                        continue
                    else:
                        geom_type_name = geom_type.title()

                    geom_data = {
                        'type': geom_type_name,
                        'coordinates': coordinates
                    }
                    geometry = GEOSGeometry(json.dumps(geom_data))
                    batch_geometries.append((idx, feature, geometry))
                except Exception as e:
                    import_log.add(f"Failed to create geometry for feature {idx}: {str(e)}", 'Find Coordinate Duplicates')
                    logger.error(f"Failed to create geometry for feature {idx}: {traceback.format_exc()}")
                    unique_features.append(feature)

            if not batch_geometries:
                continue

            # Single database query for the entire batch
            try:
                geometries = [geom for _, _, geom in batch_geometries]
                existing_features = FeatureStore.objects.filter(
                    user_id=user_id,
                    geometry__in=geometries
                ).values('id', 'geojson', 'timestamp', 'geometry')

                # Create a lookup map for existing features
                existing_lookup = {}
                for existing in existing_features:
                    geom_wkt = existing['geometry'].wkt
                    if geom_wkt not in existing_lookup:
                        existing_lookup[geom_wkt] = []
                    existing_lookup[geom_wkt].append(existing)

                # Check each feature in the batch
                for idx, feature, geometry in batch_geometries:
                    geom_wkt = geometry.wkt
                    if geom_wkt in existing_lookup:
                        # This is a duplicate
                        duplicate_info = {
                            'feature': feature,
                            'existing_features': existing_lookup[geom_wkt]
                        }
                        duplicate_features.append(duplicate_info)

                        feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                        existing_count = len(existing_lookup[geom_wkt])
                        import_log.add(f"Coordinate duplicate found: '{feature_name}' ({geom_type}) matches {existing_count} existing feature(s)", 'Duplicate Detection', DatabaseLogLevel.INFO)
                    else:
                        unique_features.append(feature)

            except Exception as e:
                import_log.add(f"Batch query encountered an issue, processing individually", 'Duplicate Detection', DatabaseLogLevel.WARNING)
                # Log internal error details for debugging
                logger.warning(f"Batch query failed for {geom_type} features: {str(e)}")
                logger.error(f"Batch query error traceback: {traceback.format_exc()}")
                # Fall back to individual processing for this batch
                for idx, feature, coordinates in batch_features:
                    unique_features.append(feature)

    # Log summary
    if duplicate_features:
        import_log.add(f"Found {len(duplicate_features)} features that already exist in your library", "Duplicate Detection", DatabaseLogLevel.INFO)
    else:
        import_log.add("No duplicate features found in your existing library", "Duplicate Detection", DatabaseLogLevel.INFO)

    return unique_features, duplicate_features, import_log


def _find_existing_features_by_coordinates(coordinates: List, geom_type: str, user_id: int) -> List[Dict]:
    """Find existing features in the database with matching coordinates."""
    try:
        # Create a GEOSGeometry object for spatial queries
        if geom_type == 'point':
            # For points, use exact coordinate matching
            geom_data = {
                'type': 'Point',
                'coordinates': coordinates
            }
            geometry = GEOSGeometry(json.dumps(geom_data))

            # Find features with the same point coordinates
            existing_features = FeatureStore.objects.filter(
                user_id=user_id,
                geometry__equals=geometry
            ).values('id', 'geojson', 'timestamp')

        elif geom_type == 'linestring':
            # For linestrings, check if coordinates match exactly
            geom_data = {
                'type': 'LineString',
                'coordinates': coordinates
            }
            geometry = GEOSGeometry(json.dumps(geom_data))

            existing_features = FeatureStore.objects.filter(
                user_id=user_id,
                geometry__equals=geometry
            ).values('id', 'geojson', 'timestamp')

        elif geom_type == 'polygon':
            # For polygons, check if coordinates match exactly
            geom_data = {
                'type': 'Polygon',
                'coordinates': coordinates
            }
            geometry = GEOSGeometry(json.dumps(geom_data))

            existing_features = FeatureStore.objects.filter(
                user_id=user_id,
                geometry__equals=geometry
            ).values('id', 'geojson', 'timestamp')

        elif geom_type == 'multilinestring':
            # For multilinestrings, check if coordinates match exactly
            geom_data = {
                'type': 'MultiLineString',
                'coordinates': coordinates
            }
            geometry = GEOSGeometry(json.dumps(geom_data))

            existing_features = FeatureStore.objects.filter(
                user_id=user_id,
                geometry__equals=geometry
            ).values('id', 'geojson', 'timestamp')

        elif geom_type == 'multipolygon':
            # For multipolygons, check if coordinates match exactly
            geom_data = {
                'type': 'MultiPolygon',
                'coordinates': coordinates
            }
            geometry = GEOSGeometry(json.dumps(geom_data))

            existing_features = FeatureStore.objects.filter(
                user_id=user_id,
                geometry__equals=geometry
            ).values('id', 'geojson', 'timestamp')

        elif geom_type == 'multipoint':
            # For multipoints, check if coordinates match exactly
            geom_data = {
                'type': 'MultiPoint',
                'coordinates': coordinates
            }
            geometry = GEOSGeometry(json.dumps(geom_data))

            existing_features = FeatureStore.objects.filter(
                user_id=user_id,
                geometry__equals=geometry
            ).values('id', 'geojson', 'timestamp')

        elif geom_type == 'geometrycollection':
            # For geometry collections, we need to handle this differently
            # since GeometryCollection uses 'geometries' not 'coordinates'
            # and contains multiple geometries of different types
            return _find_geometry_collection_duplicates(coordinates, user_id)

        else:
            return []

        # Convert to list and add feature info
        result = []
        for feature in existing_features:
            geojson_data = feature['geojson']
            result.append({
                'id': feature['id'],
                'name': geojson_data.get('properties', {}).get('name', 'Unnamed'),
                'type': geojson_data.get('geometry', {}).get('type', 'Unknown'),
                'timestamp': feature['timestamp'].isoformat(),
                'geojson': geojson_data
            })

        return result

    except Exception as e:
        # Log internal error details but don't expose to user
        logger.error(f"Error finding existing features by coordinates: {type(e).__name__}: {str(e)}")
        logger.error(f"Coordinate lookup error traceback: {traceback.format_exc()}")
        return []


def _find_geometry_collection_duplicates(geometries: List, user_id: int) -> List[Dict]:
    """
    Find existing features that match any geometry within a GeometryCollection.
    Returns the first match found for any geometry in the collection.
    """
    try:
        # Check each geometry in the collection for duplicates
        for geometry in geometries:
            geom_type = geometry.get('type', '').lower()
            coordinates = geometry.get('coordinates', [])

            if coordinates:
                # Recursively check this geometry for duplicates
                existing_features = _find_existing_features_by_coordinates(coordinates, geom_type, user_id)
                if existing_features:
                    # Return the first match found
                    return existing_features

        # No duplicates found in any geometry
        return []

    except Exception as e:
        logger.error(f"Error finding geometry collection duplicates: {type(e).__name__}: {str(e)}")
        logger.error(f"Geometry collection error traceback: {traceback.format_exc()}")
        return []


# TODO: allow re-import of old previously uploaded by re-uploading it

def _get_logs_by_log_id(log_id):
    """Fetch logs from DatabaseLogging table by log_id"""
    logs = DatabaseLogging.objects.filter(log_id=log_id).order_by('id')
    return [{'timestamp': log.timestamp.isoformat(), 'msg': log.text, 'source': log.source, 'level': log.level} for log in logs]


def _delete_logs_by_log_id(log_id):
    """Delete all logs from DatabaseLogging table by log_id"""
    deleted_count = DatabaseLogging.objects.filter(log_id=log_id).delete()[0]
    return deleted_count


class DocumentForm(forms.Form):
    file = forms.FileField()


@login_required_401
@csrf_protect
def upload_item(request):
    """
    Main upload endpoint - now uses async processing by default.
    """
    if request.method == 'POST':
        form = DocumentForm(request.POST, request.FILES)
        if form.is_valid():
            uploaded_file = request.FILES['file']
            file_name = uploaded_file.name

            # Comprehensive file validation using security module
            validator = SecureFileValidator()
            is_valid, validation_message = validator.validate_file(uploaded_file)

            if not is_valid:
                logger.warning(f"File validation failed for {file_name}: {validation_message}")
                return JsonResponse({
                    'success': False,
                    'msg': f'File validation failed: {validation_message}',
                    'job_id': None
                }, status=400)

            # Read file data after validation
            file_data = uploaded_file.read()

            # Create a processing job
            job_id = status_tracker.create_job(file_name, request.user.id)

            # Start background processing
            if upload_job.start_upload_job(job_id, file_data, file_name, request.user.id):
                return JsonResponse({
                    'success': True,
                    'msg': 'File uploaded successfully, processing started',
                    'job_id': job_id
                }, status=200)
            else:
                return JsonResponse({
                    'success': False,
                    'msg': 'Failed to start file processing',
                    'job_id': None
                }, status=500)
        else:
            # Try to get filename even if form validation failed
            filename = "unknown file"
            if 'file' in request.FILES:
                filename = request.FILES['file'].name
            return JsonResponse({
                'success': False,
                'msg': f'Invalid upload structure for file "{filename}"',
                'job_id': None
            }, status=400)
    else:
        return HttpResponse(status=405)


@login_required_401
def get_processing_status(request, job_id):
    """
    Get the processing status of a file upload job.
    """
    if not job_id:
        return JsonResponse({'success': False, 'msg': 'Job ID not provided'}, status=400)

    # Get job status
    job_status = status_tracker.get_job_status(job_id)

    if not job_status:
        return JsonResponse({'success': False, 'msg': 'Job not found'}, status=404)

    # Check if user owns this job
    job = status_tracker.get_job(job_id)
    if not job or job.user_id != request.user.id:
        return JsonResponse({'success': False, 'msg': 'Not authorized to view this job'}, status=403)

    return JsonResponse({
        'success': True,
        'job_status': job_status
    }, status=200)


@login_required_401
def get_user_processing_jobs(request):
    """
    Get all processing jobs for the current user.
    """
    user_jobs = status_tracker.get_user_jobs(request.user.id)

    job_statuses = []
    for job in user_jobs:
        job_status = status_tracker.get_job_status(job.job_id)
        if job_status:
            job_statuses.append(job_status)

    return JsonResponse({
        'success': True,
        'jobs': job_statuses
    }, status=200)


@login_required_401
def fetch_import_history_item(request, item_id: int):
    item = ImportQueue.objects.get(id=item_id)
    if item.user_id != request.user.id:
        return JsonResponse({'success': False, 'msg': 'not authorized to view this item', 'code': 403}, status=400)

    response = HttpResponse(item.raw_kml, content_type='application/octet-stream')
    response['Content-Disposition'] = 'attachment; filename="%s"' % item.original_filename
    return response


@login_required_401
@csrf_protect
def delete_import_item(request, id):
    if request.method == 'DELETE':
        try:
            queue = ImportQueue.objects.get(id=id)
        except ImportQueue.DoesNotExist:
            return JsonResponse({'success': False, 'msg': 'ID does not exist', 'code': 404}, status=400)

        # Check if user owns this item
        if queue.user_id != request.user.id:
            return JsonResponse({'success': False, 'msg': 'Not authorized to delete this item', 'code': 403}, status=400)

        # Start async delete job
        job_id = delete_job.start_delete_job(id, request.user.id, queue.original_filename)
        
        if job_id:
            return JsonResponse({
                'success': True, 
                'msg': 'Delete job started',
                'job_id': job_id
            })
        else:
            return JsonResponse({
                'success': False, 
                'msg': 'Failed to start delete job'
            }, status=500)
    return HttpResponse(status=405)


@login_required_401
@csrf_protect
@require_http_methods(["DELETE"])
def bulk_delete_import_items(request):
    """Bulk delete multiple import queue items using async jobs"""
    try:
        data = json.loads(request.body)
        if not isinstance(data, dict) or 'ids' not in data:
            return JsonResponse({'success': False, 'msg': 'Invalid data format. Expected {"ids": [1, 2, 3]}', 'code': 400}, status=400)

        item_ids = data['ids']
        if not isinstance(item_ids, list):
            return JsonResponse({'success': False, 'msg': 'ids must be a list', 'code': 400}, status=400)

        if not item_ids:
            return JsonResponse({'success': False, 'msg': 'No items to delete', 'code': 400}, status=400)

        # Validate that all IDs are integers
        try:
            item_ids = [int(id) for id in item_ids]
        except (ValueError, TypeError):
            return JsonResponse({'success': False, 'msg': 'All IDs must be integers', 'code': 400}, status=400)

        # Get all items that belong to the current user
        items = ImportQueue.objects.filter(id__in=item_ids, user=request.user)
        found_ids = list(items.values_list('id', flat=True))

        # Check if any requested IDs were not found or don't belong to the user
        missing_ids = set(item_ids) - set(found_ids)
        if missing_ids:
            return JsonResponse({
                'success': False,
                'msg': f'Items not found or not authorized: {list(missing_ids)}',
                'code': 404
            }, status=400)

        # Start delete jobs for each item
        job_ids = []
        for item in items:
            job_id = delete_job.start_delete_job(item.id, request.user.id, item.original_filename)
            if job_id:
                job_ids.append(job_id)

        if job_ids:
            return JsonResponse({
                'success': True,
                'msg': f'Started {len(job_ids)} delete job{"s" if len(job_ids) != 1 else ""}',
                'job_ids': job_ids,
                'started_count': len(job_ids)
            })
        else:
            return JsonResponse({
                'success': False,
                'msg': 'Failed to start any delete jobs'
            }, status=500)

    except json.JSONDecodeError:
        return JsonResponse({'success': False, 'msg': 'Invalid JSON data', 'code': 400}, status=400)
    except Exception as e:
        # Log internal error details for debugging, but don't expose to user
        logger.error(f'Bulk delete error: {type(e).__name__}: {str(e)}')
        logger.error(f'Bulk delete error traceback: {traceback.format_exc()}')
        return JsonResponse({'success': False, 'msg': 'Internal server error occurred', 'code': 500}, status=500)


@login_required_401
@csrf_protect
@require_http_methods(["PUT", "PATCH"])
def update_import_item(request, item_id):
    try:
        queue = ImportQueue.objects.get(id=item_id)
    except ImportQueue.DoesNotExist:
        return JsonResponse({'success': False, 'msg': 'ID does not exist', 'code': 404}, status=400)
    if queue.user_id != request.user.id:
        return JsonResponse({'success': False, 'msg': 'not authorized to edit this item', 'code': 403}, status=403)

    # Prevent updating items that have already been imported to the feature store
    if queue.imported:
        return JsonResponse({
            'success': False,
            'msg': 'Cannot update items that have already been imported to the feature store',
            'code': 400
        }, status=400)

    try:
        data = json.loads(request.body)
        if not isinstance(data, dict) or 'features' not in data:
            raise ValueError('Invalid data format. Expected {"features": [{feature with id}, ...]}')

        features_to_update = data['features']
        if not isinstance(features_to_update, list):
            raise ValueError('features must be a list')
    except (json.JSONDecodeError, ValueError) as e:
        return JsonResponse({'success': False, 'msg': str(e), 'code': 400}, status=400)

    # Build a lookup map of feature ID to updated feature
    updates_by_id = {}
    for feature in features_to_update:
        # Validate and parse the feature
        c = None
        geom_type = feature.get('geometry', {}).get('type', '').lower()
        match geom_type:
            case 'point':
                c = PointFeature
            case 'linestring':
                c = LineStringFeature
            case 'polygon':
                c = PolygonFeature
            case _:
                continue

        if c is None:
            continue

        # Parse the feature to validate it
        try:
            parsed_feature = c(**feature)
            feature_json = json.loads(parsed_feature.model_dump_json())
            feature_id = feature_json.get('properties', {}).get('id')

            if not feature_id:
                logger.warning(f"Skipping feature without ID: {feature.get('properties', {}).get('name', 'Unnamed')}")
                continue

            updates_by_id[feature_id] = feature_json
        except Exception as e:
            logger.error(f"Error parsing feature: {e}")
            logger.error(f"Feature parsing error traceback: {traceback.format_exc()}")
            continue

    # Update features in the geofeatures array by matching IDs
    updated_count = 0
    for i, existing_feature in enumerate(queue.geofeatures):
        feature_id = existing_feature.get('properties', {}).get('id')
        if feature_id and feature_id in updates_by_id:
            # Preserve protected tags from original feature
            original_tags = existing_feature.get('properties', {}).get('tags', [])
            if not isinstance(original_tags, list):
                original_tags = []
            protected_tags = [tag for tag in original_tags if is_protected_tag(tag, CONST_INTERNAL_TAGS)]
            
            # Filter protected tags from incoming feature
            updated_feature = updates_by_id[feature_id]
            new_tags = updated_feature.get('properties', {}).get('tags', [])
            if not isinstance(new_tags, list):
                new_tags = []
            filtered_tags = filter_protected_tags(new_tags, CONST_INTERNAL_TAGS)
            
            # Combine filtered user tags with preserved protected tags
            updated_feature['properties']['tags'] = filtered_tags + protected_tags
            queue.geofeatures[i] = updated_feature
            updated_count += 1

    # Save the updated queue
    queue.save()

    return JsonResponse({
        'success': True,
        'msg': f'Successfully updated {updated_count} feature(s)',
        'updated_count': updated_count
    })


@login_required_401
@csrf_protect
@require_http_methods(["POST"])
def import_to_featurestore(request, item_id):
    try:
        import_item = ImportQueue.objects.get(id=item_id)
    except ImportQueue.DoesNotExist:
        return JsonResponse({'success': False, 'msg': 'ID does not exist', 'code': 404}, status=400)
    if import_item.user_id != request.user.id:
        return JsonResponse({'success': False, 'msg': 'not authorized to edit this item', 'code': 403}, status=403)

    # Prevent importing items that have already been imported to the feature store
    if import_item.imported:
        return JsonResponse({
            'success': False,
            'msg': 'This item has already been imported to the feature store',
            'code': 400
        }, status=400)

    # Parse request body to get import_custom_icons flag
    import_custom_icons = True  # Default to True for backward compatibility
    try:
        if request.body:
            data = json.loads(request.body)
            if isinstance(data, dict):
                import_custom_icons = data.get('import_custom_icons', True)
    except (json.JSONDecodeError, ValueError) as e:
        logger.warning(f"Failed to parse request body for import_custom_icons: {str(e)}, using default True")

    # Check for file-level duplicates before importing
    # Only block duplicates that are still in the queue (not yet imported)
    # Allow re-importing files that were previously imported
    if import_item.geojson_hash:
        # Check if there are other items in queue with same hash (uploaded earlier)
        earlier_duplicates = ImportQueue.objects.filter(
            user=request.user,
            geojson_hash=import_item.geojson_hash,
            imported=False,
            timestamp__lt=import_item.timestamp
        ).order_by('timestamp').first()
        
        if earlier_duplicates:
            return JsonResponse({
                'success': False,
                'msg': f'This file is a duplicate of "{earlier_duplicates.original_filename}" which is already in the import queue',
                'code': 409
            }, status=409)

    # Log import start
    logger.info(f"Starting import of {len(import_item.geofeatures)} features from '{import_item.original_filename}' for user {request.user.id}")

    # Prepare features for bulk import
    features_to_create = []
    existing_hashes = set()
    current_batch_hashes = set()  # Track hashes in current import batch

    # Get existing feature hashes for this user to avoid duplicates
    logger.info(f"Checking for duplicate features in user {request.user.id}'s feature library")
    existing_features = FeatureStore.objects.filter(user=request.user).values_list('geojson_hash', flat=True)
    existing_hashes.update(existing_features)
    logger.info(f"User has {len(existing_hashes)} existing features in library")

    # Log geometry type breakdown for debugging
    geometry_types = {}
    for feature in import_item.geofeatures:
        if 'geometry' in feature and feature['geometry']:
            geom_type = feature['geometry']['type']
            geometry_types[geom_type] = geometry_types.get(geom_type, 0) + 1
    logger.info(f"Importing {len(import_item.geofeatures)} features with geometry types: {geometry_types}")

    i = 0
    for feature in import_item.geofeatures:
        c = None
        if 'geometry' not in feature or not feature['geometry']:
            logger.warning(f"Skipping feature {i} due to missing or empty geometry: {feature.get('properties', {}).get('name', 'Unnamed')}")
            i += 1
            continue

        geometry_type = feature['geometry']['type'].lower()
        match geometry_type:
            case 'point':
                c = PointFeature
            case 'multipoint':
                c = PointFeature
            case 'linestring':
                c = LineStringFeature
            case 'multilinestring':
                c = MultiLineStringFeature
            case 'polygon':
                c = PolygonFeature
            case 'multipolygon':
                c = PolygonFeature
            case _:
                feature_name = feature.get('properties', {}).get('name', 'Unnamed')
                logger.warning(f"Skipping feature {i} '{feature_name}' due to unsupported geometry type: {geometry_type}")
                i += 1
                continue
        assert c is not None

        # Strip icon properties if import_custom_icons is False
        if not import_custom_icons:
            feature = strip_icon_properties(feature.copy())

        feature_instance = c(**feature)
        # Generate auto tags (geocoding is already done during processing, this just adds type/date tags)
        existing_tags = feature_instance.properties.tags or []
        auto_tags = generate_auto_tags(feature_instance)
        # Merge tags, avoiding duplicates
        all_tags = list(existing_tags) + [tag for tag in auto_tags if tag not in existing_tags]
        feature_instance.properties.tags = all_tags

        # Create the GeoJSON data
        geojson_data = json.loads(feature_instance.model_dump_json())

        # Generate hash-based ID for the feature
        feature_hash = generate_feature_hash(geojson_data)

        # Check if this feature already exists for this user or in current batch
        if feature_hash in existing_hashes or feature_hash in current_batch_hashes:
            # Skip importing duplicate features
            feature_name = geojson_data.get('properties', {}).get('name', 'Unnamed')
            logger.info(f"Skipping duplicate feature '{feature_name}' with hash {feature_hash[:16]}... for user {request.user.id}")
            i += 1
            continue

        # Add to current batch hashes to prevent duplicates within the same import
        current_batch_hashes.add(feature_hash)

        # Update the feature's ID in the GeoJSON data
        geojson_data['properties']['id'] = feature_hash

        # Create geometry object for spatial queries
        geometry = None
        if 'geometry' in geojson_data and geojson_data['geometry']:
            try:
                # Ensure coordinates are properly formatted for GEOSGeometry
                geom_data = geojson_data['geometry'].copy()

                # Handle 3D coordinates by ensuring they're properly structured
                if geom_data['type'] == 'Point':
                    coords = geom_data['coordinates']
                    # Ensure Point has exactly 3 coordinates (x, y, z) or 2 (x, y)
                    if len(coords) == 2:
                        coords = [coords[0], coords[1], 0.0]  # Add Z=0 for 2D points
                    elif len(coords) == 3:
                        coords = [coords[0], coords[1], coords[2]]  # Keep 3D
                    geom_data['coordinates'] = coords

                elif geom_data['type'] == 'LineString':
                    coords = geom_data['coordinates']
                    # Ensure each coordinate in LineString has 3 dimensions
                    geom_data['coordinates'] = [
                        [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                        for coord in coords
                    ]

                elif geom_data['type'] == 'Polygon':
                    coords = geom_data['coordinates']
                    # Ensure each coordinate in Polygon has 3 dimensions
                    geom_data['coordinates'] = [
                        [
                            [coord[0], coord[1], coord[2] if len(coord) > 2 else 0.0]
                            for coord in ring
                        ]
                        for ring in coords
                    ]

                geometry = GEOSGeometry(json.dumps(geom_data))
            except Exception as e:
                # Log internal error details for debugging - don't expose to user
                logger.warning(f"Error creating geometry for feature {i}: {type(e).__name__}: {str(e)}")
                logger.error(f"Geometry creation error traceback for feature {i}: {traceback.format_exc()}")

        # Add to bulk creation list
        features_to_create.append(FeatureStore(
            geojson=geojson_data,
            geojson_hash=feature_hash,
            geometry=geometry,
            source=import_item,
            user=request.user
        ))
        i += 1

    # Track successful feature creation
    successful_imports = 0

    # Bulk create all features at once for better performance
    if features_to_create:
        try:
            logger.info(f"Importing {len(features_to_create)} unique features to database for user {request.user.id}")

            FeatureStore.objects.bulk_create(features_to_create, batch_size=1000)
            successful_imports = len(features_to_create)
            logger.info(f"Successfully imported {successful_imports} features in bulk for user {request.user.id}")
        except Exception as e:
            logger.warning(f"Bulk import failed for user {request.user.id}, falling back to individual imports: {str(e)}")
            logger.error(f"Bulk import error traceback: {traceback.format_exc()}")
            # Fallback to individual creation if bulk fails
            for feature in features_to_create:
                try:
                    feature.save()
                    successful_imports += 1
                except Exception as individual_error:
                    logger.error(f"Error creating individual feature for user {request.user.id}: {individual_error}")
                    logger.error(f"Individual feature creation error traceback: {traceback.format_exc()}")
                    # If it's a duplicate key error, that's expected and we can continue
                    if "duplicate key" not in str(individual_error).lower():
                        logger.error(f"Unexpected error creating feature for user {request.user.id}: {individual_error}")

            logger.info(f"Fallback import completed for user {request.user.id}: {successful_imports}/{len(features_to_create)} features imported")

    # Log final summary
    total_processed = len(import_item.geofeatures)
    total_imported = successful_imports
    total_skipped = total_processed - len(features_to_create)  # Features skipped before creation attempt

    # Only mark as imported and proceed with cleanup if at least one feature was successfully created
    if successful_imports > 0:
        logger.info(f"Import completed for user {request.user.id}: {total_imported} features imported, {total_skipped} skipped (already exist)")

        # Mark as imported only after successful feature creation
        import_item.imported = True

        # Delete logs before clearing the log_id
        if import_item.log_id:
            _delete_logs_by_log_id(str(import_item.log_id))

        # Erase some unneeded data since it's not needed anymore now that it's in the feature store.
        import_item.geofeatures = []
        import_item.log_id = None

        import_item.save()
        
        # Broadcast WebSocket event for item import
        _broadcast_item_imported(request.user.id, item_id)

        return JsonResponse({'success': True, 'msg': f'Successfully imported {total_imported} features ({total_skipped} already existed)'})
    else:
        # No features were successfully imported
        logger.warning(f"Import failed for user {request.user.id}: No features were imported from '{import_item.original_filename}'")
        
        # Determine reason for failure
        if len(features_to_create) == 0:
            if total_processed == 0:
                reason = "No features found in the file"
            else:
                reason = f"All {total_processed} features were skipped (duplicates, missing geometry, or unsupported types)"
        else:
            reason = f"Failed to create {len(features_to_create)} features in the database"
        
        return JsonResponse({
            'success': False,
            'msg': f'No features were imported. {reason}.',
            'code': 400
        }, status=400)


def _hash_kml(b: str):
    if not isinstance(b, bytes):
        b = b.encode()
    return hashlib.sha256(b).hexdigest()


def _broadcast_item_deleted(user_id: int, item_id: int):
    """Broadcast WebSocket event when an item is deleted."""
    from channels.layers import get_channel_layer
    from asgiref.sync import async_to_sync
    
    channel_layer = get_channel_layer()
    if channel_layer:
        # Broadcast to general realtime channel
        async_to_sync(channel_layer.group_send)(
            f"realtime_{user_id}",
            {
                'type': 'import_queue_item_deleted',
                'data': {'id': item_id}
            }
        )
        
        # Also broadcast to upload status channel for this specific item
        async_to_sync(channel_layer.group_send)(
            f"upload_status_{user_id}_{item_id}",
            {
                'type': 'item_deleted',
                'data': {'id': item_id}
            }
        )


def _broadcast_items_deleted(user_id: int, item_ids: list):
    """Broadcast WebSocket event when multiple items are deleted."""
    from channels.layers import get_channel_layer
    from asgiref.sync import async_to_sync
    
    channel_layer = get_channel_layer()
    if channel_layer:
        async_to_sync(channel_layer.group_send)(
            f"realtime_{user_id}",
            {
                'type': 'import_queue_items_deleted',
                'data': {'ids': item_ids}
            }
        )


def _broadcast_item_imported(user_id: int, item_id: int):
    """Broadcast WebSocket event when an item is imported."""
    from channels.layers import get_channel_layer
    from asgiref.sync import async_to_sync
    from api.models import ImportQueue
    
    channel_layer = get_channel_layer()
    if channel_layer:
        # Get item details for history broadcast
        try:
            item = ImportQueue.objects.get(id=item_id)
            item_data = {
                'id': item_id,
                'original_filename': item.original_filename,
                'timestamp': item.timestamp.isoformat()
            }
        except ImportQueue.DoesNotExist:
            item_data = {'id': item_id}
        
        # Broadcast to import queue module
        async_to_sync(channel_layer.group_send)(
            f"realtime_{user_id}",
            {
                'type': 'import_queue_item_imported',
                'data': {'id': item_id}
            }
        )
        
        # Broadcast to import history module
        async_to_sync(channel_layer.group_send)(
            f"realtime_{user_id}",
            {
                'type': 'import_history_item_added',
                'data': item_data
            }
        )
