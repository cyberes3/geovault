import hashlib
import json
import logging
import traceback
from typing import List, Dict, Tuple, Optional

from django import forms
from django.contrib.gis.geos import GEOSGeometry
from django.core.serializers.json import DjangoJSONEncoder
from django.db import IntegrityError
from django.http import HttpResponse, JsonResponse
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_http_methods

from data.models import ImportQueue, FeatureStore, DatabaseLogging
from geo_lib.processing.kml.kml import normalize_kml_for_comparison, kml_to_geojson
from geo_lib.processing.tagging import generate_auto_tags
from geo_lib.types.feature import geojson_to_geofeature
from geo_lib.security.file_validation import SecureFileValidator, secure_kmz_to_kml, FileValidationError, SecurityError
from geo_lib.security.file_validation import validate_kml_content
from geo_lib.types.feature import PointFeature, PolygonFeature, LineStringFeature
from geo_lib.website.auth import login_required_401
from geo_lib.feature_id import generate_feature_hash
from geo_lib.logging.database import log_to_db, DatabaseLogLevel, DatabaseLogSource

logger = logging.getLogger(__name__)


def _strip_duplicate_features(features, user_id, log_id):
    """Remove 100% duplicate features and log the process."""
    if not features:
        return features, 0
    
    # Track features by hash
    seen_hashes = set()
    unique_features = []
    duplicates_removed = 0
    
    for feature in features:
        # Generate hash for this feature
        feature_hash = generate_feature_hash(feature)
        
        if feature_hash in seen_hashes:
            # This is a duplicate
            duplicates_removed += 1
            feature_name = feature.get('properties', {}).get('name', 'Unnamed')
            feature_type = feature.get('geometry', {}).get('type', 'Unknown')
            
            # Log the duplicate removal
            try:
                log_to_db(
                    f"Removed duplicate feature: '{feature_name}' ({feature_type})",
                    DatabaseLogLevel.INFO,
                    user_id,
                    DatabaseLogSource.IMPORT,
                    log_id
                )
            except Exception as e:
                logger.error(f"Failed to log duplicate removal: {e}")
        else:
            # This is a unique feature
            seen_hashes.add(feature_hash)
            unique_features.append(feature)
    
    # Log summary
    if duplicates_removed > 0:
        try:
            log_to_db(
                f"Removed {duplicates_removed} duplicate features. Processing {len(unique_features)} unique features.",
                DatabaseLogLevel.INFO,
                user_id,
                DatabaseLogSource.IMPORT,
                log_id
            )
        except Exception as e:
            logger.error(f"Failed to log duplicate summary: {e}")
    
    return unique_features, duplicates_removed


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


def _find_coordinate_duplicates(features: List[Dict], user_id: int) -> Tuple[List[Dict], List[Dict]]:
    """
    Find features that have duplicate coordinates in the existing featurestore.
    Returns (unique_features, duplicate_features_with_originals)
    """
    if not features:
        return features, []
    
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
        existing_features = _find_existing_features_by_coordinates(
            coordinates, geom_type, user_id
        )
        
        if existing_features:
            # This is a duplicate - add original feature info
            duplicate_info = {
                'feature': feature,
                'existing_features': existing_features
            }
            duplicate_features.append(duplicate_info)
        else:
            unique_features.append(feature)
    
    return unique_features, duplicate_features


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
        logger.error(f"Error finding existing features by coordinates: {e}")
        return []


# TODO: allow re-import of old previously uploaded by re-uploading it

def _get_logs_by_log_id(log_id):
    """Fetch logs from DatabaseLogging table by log_id"""
    logs = DatabaseLogging.objects.filter(attributes__log_id=log_id).order_by('timestamp')
    return [{'timestamp': log.timestamp.isoformat(), 'msg': log.text} for log in logs]


def _delete_logs_by_log_id(log_id):
    """Delete all logs from DatabaseLogging table by log_id"""
    deleted_count = DatabaseLogging.objects.filter(attributes__log_id=log_id).delete()[0]
    return deleted_count


class DocumentForm(forms.Form):
    file = forms.FileField()


@login_required_401
@csrf_protect
def upload_item(request):
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
                    'id': None
                }, status=400)

            # Read file data after validation
            file_data = uploaded_file.read()

            try:
                # Determine file type and process accordingly
                filename_lower = file_name.lower()
                if filename_lower.endswith('.kmz') or (isinstance(file_data, bytes) and file_data.startswith(b'PK')):
                    # It's a KMZ file - use secure KMZ to KML conversion
                    kml_doc = secure_kmz_to_kml(file_data)
                else:
                    # It's a KML file - decode and validate directly
                    if isinstance(file_data, bytes):
                        kml_content = file_data.decode('utf-8')
                    else:
                        kml_content = file_data

                    # Validate KML content (don't modify it)
                    validate_kml_content(kml_content)
                    kml_doc = kml_content

            except (SecurityError, FileValidationError) as e:
                logger.error(f"Secure file processing failed for {file_name}: {str(e)}")
                return JsonResponse({
                    'success': False,
                    'msg': f'Secure file processing failed: {str(e)}',
                    'id': None
                }, status=400)
            except Exception as e:
                logger.error(f"Unexpected error processing file {file_name}: {traceback.format_exc()}")
                return JsonResponse({
                    'success': False,
                    'msg': f'Failed to process KML/KMZ file "{file_name}"',
                    'id': None
                }, status=400)

            # Normalize KML content for comparison (handles KML vs KMZ differences)
            normalized_kml = normalize_kml_for_comparison(kml_doc)

            # Check for duplicates more comprehensively using normalized content
            kml_hash = _hash_kml(normalized_kml)

            # Check if this exact KML content already exists for this user (regardless of filename)
            # First check for non-unparsable files
            existing_by_content = ImportQueue.objects.filter(
                raw_kml_hash=kml_hash,
                user=request.user,
                unparsable=False
            ).first()

            if existing_by_content:
                if existing_by_content.imported:
                    return JsonResponse({
                        'success': False,
                        'msg': f'This KML/KMZ file has already been imported to the feature store (originally as "{existing_by_content.original_filename}")',
                        'id': None
                    }, status=200)  # Changed to 200 for benign duplicate content
                else:
                    return JsonResponse({
                        'success': False,
                        'msg': f'This KML/KMZ file is already in your import queue (originally as "{existing_by_content.original_filename}")',
                        'id': None
                    }, status=200)  # Changed to 200 for benign duplicate content

            # Check for unparsable files with the same hash
            existing_unparsable = ImportQueue.objects.filter(
                raw_kml_hash=kml_hash,
                user=request.user,
                unparsable=True
            ).first()

            if existing_unparsable:
                # If there's an unparsable record, we need to handle the unique constraint
                # We'll update the existing record instead of creating a new one
                existing_unparsable.raw_kml = kml_doc
                existing_unparsable.original_filename = file_name
                existing_unparsable.imported = False
                existing_unparsable.unparsable = False
                existing_unparsable.geofeatures = []
                existing_unparsable.save()

                # Return success with the existing record's ID
                msg = 'upload successful (replaced previous unparsable record)'
                return JsonResponse({'success': True, 'msg': msg, 'id': existing_unparsable.id}, status=200)

            # Check if this exact filename already exists for this user (regardless of content)
            # Exclude unparsable files from duplicate checking
            existing_by_filename = ImportQueue.objects.filter(
                original_filename=file_name,
                user=request.user,
                unparsable=False
            ).first()

            if existing_by_filename:
                if existing_by_filename.imported:
                    return JsonResponse({
                        'success': False,
                        'msg': f'A file with the name "{file_name}" has already been imported to the feature store',
                        'id': None
                    }, status=200)  # Changed to 200 for benign duplicate filename
                else:
                    return JsonResponse({
                        'success': False,
                        'msg': f'A file with the name "{file_name}" is already in your import queue',
                        'id': None
                    }, status=200)  # Changed to 200 for benign duplicate filename

            # If we get here, there are no existing records with this hash, so create a new one
            try:
                # Process the KML/KMZ file synchronously
                try:
                    geojson_data, kml_conv_messages = kml_to_geojson(kml_doc)
                    geo_features, typing_messages = geojson_to_geofeature(geojson_data)
                    
                    # Convert features to JSON format
                    features_json = [json.loads(x.model_dump_json()) for x in geo_features]
                    
                    # Create import queue item first to get log_id
                    import_queue = ImportQueue.objects.create(
                        raw_kml=kml_doc,
                        raw_kml_hash=kml_hash,
                        original_filename=file_name,
                        user=request.user,
                        geofeatures=features_json
                    )
                    
                    # Strip duplicate features and log the process
                    unique_features, duplicates_removed = _strip_duplicate_features(
                        features_json, 
                        request.user.id, 
                        str(import_queue.log_id)
                    )
                    
                    # Update the import queue with deduplicated features
                    import_queue.geofeatures = unique_features
                    import_queue.save()
                    
                    # Log upload completion
                    try:
                        log_to_db(
                            f"Upload and processing successful - {len(features_json)} features found, {duplicates_removed} duplicates removed, {len(unique_features)} unique features ready for import",
                            DatabaseLogLevel.INFO,
                            request.user.id,
                            DatabaseLogSource.IMPORT,
                            str(import_queue.log_id)
                        )
                    except Exception as e:
                        logger.error(f"Failed to log upload completion: {e}")
                    
                    msg = f'upload and processing successful - {len(unique_features)} unique features ready for import'
                    if duplicates_removed > 0:
                        msg += f' ({duplicates_removed} duplicates removed)'
                    
                    return JsonResponse({'success': True, 'msg': msg, 'id': import_queue.id}, status=200)
                    
                except Exception as processing_error:
                    # If processing fails, still create the queue item but without processed data
                    # This allows the user to see the file was uploaded but processing failed
                    logger.error(f"Processing failed for {file_name}: {str(processing_error)}")
                    
                    import_queue = ImportQueue.objects.create(
                        raw_kml=kml_doc,
                        raw_kml_hash=kml_hash,
                        original_filename=file_name,
                        user=request.user,
                        geofeatures=[{"error": "processing_failed", "message": str(processing_error)}]
                    )
                    
                    msg = f'upload successful but processing failed: {str(processing_error)}'
                    return JsonResponse({'success': True, 'msg': msg, 'id': import_queue.id}, status=200)
                    
            except IntegrityError:
                error_msg = f'An unexpected error occurred while creating the import item for file "{file_name}"'
                # Raise exception in server process
                raise Exception(error_msg)

            # TODO: put the processed data into the database and then return the ID so the frontend can go to the import page and use the ID to start the import
        else:
            # Try to get filename even if form validation failed
            filename = "unknown file"
            if 'file' in request.FILES:
                filename = request.FILES['file'].name
            return JsonResponse({'success': False, 'msg': f'Invalid upload structure for file "{filename}"', 'id': None}, status=400)

    else:
        return HttpResponse(status=405)


@login_required_401
def fetch_import_queue(request, item_id):
    if item_id is None:
        return JsonResponse({'success': False, 'msg': 'ID not provided', 'code': 400}, status=400)
    try:
        item = ImportQueue.objects.get(id=item_id)

        if item.user_id != request.user.id:
            return JsonResponse({'success': False, 'processing': False, 'msg': 'not authorized to view this item', 'code': 403}, status=400)

        if item.imported:
            return JsonResponse({'success': True, 'processing': False, 'geofeatures': None, 'log': None, 'msg': None, 'original_filename': None, 'imported': item.imported}, status=200)

        # Since processing is now synchronous, items are always ready
        # Fetch logs from database if log_id exists
        logs = []
        if item.log_id:
            logs = _get_logs_by_log_id(str(item.log_id))
        
        # Check for coordinate-based duplicates
        duplicates = []
        if item.geofeatures:
            unique_features, duplicate_features = _find_coordinate_duplicates(
                item.geofeatures, request.user.id
            )
            duplicates = duplicate_features
        
        return JsonResponse({
            'success': True, 
            'processing': False, 
            'geofeatures': item.geofeatures, 
            'log': logs, 
            'msg': None, 
            'original_filename': item.original_filename, 
            'imported': item.imported,
            'duplicates': duplicates
        }, status=200)
    except ImportQueue.DoesNotExist:
        return JsonResponse({'success': False, 'msg': 'ID does not exist', 'code': 404}, status=400)


@login_required_401
def fetch_import_waiting(request):
    user_items = ImportQueue.objects.filter(user=request.user, imported=False).values('id', 'geofeatures', 'original_filename', 'raw_kml_hash', 'log_id', 'timestamp', 'imported')
    data = json.loads(json.dumps(list(user_items), cls=DjangoJSONEncoder))
    for i, item in enumerate(data):
        count = len(item['geofeatures'])
        # Since processing is now synchronous, items are never processing
        item['processing'] = False

        # Check if there's an error in the geofeatures
        if count == 1 and item['geofeatures'] and isinstance(item['geofeatures'][0], dict) and 'error' in item['geofeatures'][0]:
            # This is an error case from failed processing
            item['feature_count'] = 0
            item['processing_failed'] = True
        else:
            item['feature_count'] = count
            item['processing_failed'] = False

        # Remove keys from response as they're not needed by frontend.
        del item['geofeatures']
        del item['log_id']
    return JsonResponse({'data': data, 'msg': None})


@login_required_401
def fetch_import_history(request):
    user_items = ImportQueue.objects.filter(user=request.user, imported=True).values('id', 'original_filename', 'timestamp')
    data = json.loads(json.dumps(list(user_items), cls=DjangoJSONEncoder))
    return JsonResponse({'data': data})


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

        # Delete logs associated with this import item before deleting the item
        _delete_logs_by_log_id(str(queue.log_id))

        queue.delete()
        return JsonResponse({'success': True})
    return HttpResponse(status=405)


@login_required_401
@csrf_protect
@require_http_methods(["DELETE"])
def bulk_delete_import_items(request):
    """Bulk delete multiple import queue items"""
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
        
        # Delete logs associated with each import item before deleting the items
        for item in items:
            _delete_logs_by_log_id(str(item.log_id))
        
        # Delete the items
        deleted_count, _ = items.delete()
        
        return JsonResponse({
            'success': True, 
            'msg': f'Successfully deleted {deleted_count} item{"s" if deleted_count != 1 else ""}',
            'deleted_count': deleted_count
        })
        
    except json.JSONDecodeError:
        return JsonResponse({'success': False, 'msg': 'Invalid JSON data', 'code': 400}, status=400)
    except Exception as e:
        logger.error(f'Bulk delete error: {str(e)}')
        return JsonResponse({'success': False, 'msg': f'Internal server error: {str(e)}', 'code': 500}, status=500)


@login_required_401
@csrf_protect
@require_http_methods(["PUT"])
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
        if not isinstance(data, list):
            raise ValueError('Invalid data format. Expected a list.')
    except (json.JSONDecodeError, ValueError) as e:
        return JsonResponse({'success': False, 'msg': str(e), 'code': 400}, status=400)

    parsed_data = []
    for feature in data:
        c = None
        match feature['type'].lower():
            case 'point':
                c = PointFeature
            case 'linestring':
                c = LineStringFeature
            case 'polygon':
                c = PolygonFeature
            case _:
                continue
        assert c is not None
        parsed_data.append(json.loads(c(**feature).model_dump_json()))

    # Update the geofeatures column with the new data
    queue.geofeatures = parsed_data
    queue.save()

    return JsonResponse({'success': True, 'msg': 'Item updated successfully'})


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

    import_item.imported = True

    # Log import start
    logger.info(f"Starting import of {len(import_item.geofeatures)} features from '{import_item.original_filename}' for user {request.user.id}")

    # Prepare features for bulk import
    features_to_create = []
    existing_hashes = set()
    current_batch_hashes = set()  # Track hashes in current import batch
    
    # Get existing feature hashes for this user to avoid duplicates
    existing_features = FeatureStore.objects.filter(user=request.user).values_list('geojson_hash', flat=True)
    existing_hashes.update(existing_features)
    
    i = 0
    for feature in import_item.geofeatures:
        c = None
        match feature['type'].lower():
            case 'point':
                c = PointFeature
            case 'linestring':
                c = LineStringFeature
            case 'polygon':
                c = PolygonFeature
            case _:
                continue
        assert c is not None

        feature_instance = c(**feature)
        feature_instance.properties.tags = generate_auto_tags(feature_instance)  # Generate the tags after the user has made their changes.

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
                print(f"Error creating geometry for feature {i}: {e}")

        # Add to bulk creation list
        features_to_create.append(FeatureStore(
            geojson=geojson_data,
            geojson_hash=feature_hash,
            geometry=geometry,
            source=import_item,
            user=request.user
        ))
        i += 1

    # Bulk create all features at once for better performance
    if features_to_create:
        try:
            logger.info(f"Importing {len(features_to_create)} unique features to database for user {request.user.id}")
            
            FeatureStore.objects.bulk_create(features_to_create, batch_size=1000)
            logger.info(f"Successfully imported {len(features_to_create)} features in bulk for user {request.user.id}")
        except Exception as e:
            logger.warning(f"Bulk import failed for user {request.user.id}, falling back to individual imports: {str(e)}")
            # Fallback to individual creation if bulk fails
            successful_imports = 0
            for feature in features_to_create:
                try:
                    feature.save()
                    successful_imports += 1
                except Exception as individual_error:
                    logger.error(f"Error creating individual feature for user {request.user.id}: {individual_error}")
                    # If it's a duplicate key error, that's expected and we can continue
                    if "duplicate key" not in str(individual_error).lower():
                        logger.error(f"Unexpected error creating feature for user {request.user.id}: {individual_error}")
            
            logger.info(f"Fallback import completed for user {request.user.id}: {successful_imports}/{len(features_to_create)} features imported")

    # Log final summary
    total_processed = len(import_item.geofeatures)
    total_imported = len(features_to_create)
    total_skipped = total_processed - total_imported
    
    logger.info(f"Import completed for user {request.user.id}: {total_imported} features imported, {total_skipped} skipped (already exist)")

    # Erase some unneded data since it's not needed anymore now that it's in the feature store.
    import_item.geofeatures = []
    import_item.log_id = None
    _delete_logs_by_log_id(str(import_item.log_id))

    import_item.save()

    return JsonResponse({'success': True, 'msg': f'Successfully imported {total_imported} features ({total_skipped} already existed)'})


def _hash_kml(b: str):
    if not isinstance(b, bytes):
        b = b.encode()
    return hashlib.sha256(b).hexdigest()
