import hashlib
import json
import traceback

from django import forms
from django.core.serializers.json import DjangoJSONEncoder
from django.db import IntegrityError
from django.http import HttpResponse, JsonResponse
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_http_methods

from data.models import ImportQueue, FeatureStore, GeoLog
from geo_lib.daemon.database.locking import DBLockManager
from geo_lib.daemon.workers.workers_lib.importer.kml import kmz_to_kml
from geo_lib.daemon.workers.workers_lib.importer.tagging import generate_auto_tags
from geo_lib.types.feature import PointFeature, PolygonFeature, LineStringFeature
from geo_lib.website.auth import login_required_401


def _get_logs_by_log_id(log_id):
    """Fetch logs from GeoLog table by log_id"""
    logs = GeoLog.objects.filter(attributes__log_id=log_id).order_by('timestamp')
    return [{'timestamp': log.timestamp.isoformat(), 'msg': log.text} for log in logs]


def _delete_logs_by_log_id(log_id):
    """Delete all logs from GeoLog table by log_id"""
    deleted_count = GeoLog.objects.filter(attributes__log_id=log_id).delete()[0]
    return deleted_count


class DocumentForm(forms.Form):
    file = forms.FileField()


@login_required_401
def upload_item(request):
    if request.method == 'POST':
        form = DocumentForm(request.POST, request.FILES)
        if form.is_valid():
            uploaded_file = request.FILES['file']

            # Check file size
            # TODO: config??
            if uploaded_file.size > 100 * 1024 * 1024:  # file size limit 100MB
                return JsonResponse({'success': False, 'msg': 'File size must be less than 100MB', 'id': None}, status=400)

            file_data = uploaded_file.read()
            file_name = uploaded_file.name

            try:
                kml_doc = kmz_to_kml(file_data)
            except:
                print(traceback.format_exc())  # TODO: logging
                return JsonResponse({'success': False, 'msg': 'failed to parse KML/KMZ', 'id': None}, status=400)

            # Check for duplicates more comprehensively
            kml_hash = _hash_kml(kml_doc)
            
            # Check if this exact KML content already exists for this user (regardless of filename)
            existing_by_content = ImportQueue.objects.filter(
                raw_kml_hash=kml_hash, 
                user=request.user
            ).first()
            
            if existing_by_content:
                if existing_by_content.imported:
                    return JsonResponse({
                        'success': False, 
                        'msg': f'This KML file has already been imported to the feature store (originally as "{existing_by_content.original_filename}")', 
                        'id': None
                    }, status=400)
                else:
                    return JsonResponse({
                        'success': False, 
                        'msg': f'This KML file is already in your import queue (originally as "{existing_by_content.original_filename}")', 
                        'id': None
                    }, status=400)
            
            # Check if this exact filename already exists for this user (regardless of content)
            existing_by_filename = ImportQueue.objects.filter(
                original_filename=file_name,
                user=request.user
            ).first()
            
            if existing_by_filename:
                if existing_by_filename.imported:
                    return JsonResponse({
                        'success': False, 
                        'msg': f'A file with the name "{file_name}" has already been imported to the feature store', 
                        'id': None
                    }, status=400)
                else:
                    return JsonResponse({
                        'success': False, 
                        'msg': f'A file with the name "{file_name}" is already in your import queue', 
                        'id': None
                    }, status=400)

            try:
                import_queue = ImportQueue.objects.create(
                    raw_kml=kml_doc, 
                    raw_kml_hash=kml_hash, 
                    original_filename=file_name, 
                    user=request.user
                )
                import_queue.save()
                msg = 'upload successful'
                return JsonResponse({'success': True, 'msg': msg, 'id': import_queue.id}, status=200)
            except IntegrityError:
                return JsonResponse({
                    'success': False, 
                    'msg': 'An unexpected error occurred while creating the import item', 
                    'id': None
                }, status=500)

            # TODO: put the processed data into the database and then return the ID so the frontend can go to the import page and use the ID to start the import
        else:
            return JsonResponse({'success': False, 'msg': 'invalid upload structure', 'id': None}, status=400)

    else:
        return HttpResponse(status=405)


@login_required_401
def fetch_import_queue(request, item_id):
    if item_id is None:
        return JsonResponse({'success': False, 'msg': 'ID not provided', 'code': 400}, status=400)
    lock_manager = DBLockManager()
    try:
        item = ImportQueue.objects.get(id=item_id)

        if item.user_id != request.user.id:
            return JsonResponse({'success': False, 'processing': False, 'msg': 'not authorized to view this item', 'code': 403}, status=400)

        if item.imported:
            return JsonResponse({'success': True, 'processing': False, 'geofeatures': None, 'log': None, 'msg': None, 'original_filename': None, 'imported': item.imported}, status=200)

        logs = _get_logs_by_log_id(str(item.log_id))
        if not lock_manager.is_locked('data_importqueue', item.id) and (len(item.geofeatures) or len(logs)):
            return JsonResponse({'success': True, 'processing': False, 'geofeatures': item.geofeatures, 'log': logs, 'msg': None, 'original_filename': item.original_filename, 'imported': item.imported}, status=200)
        return JsonResponse({'success': True, 'processing': True, 'geofeatures': [], 'log': [], 'msg': 'uploaded data still processing', 'imported': item.imported}, status=200)
    except ImportQueue.DoesNotExist:
        return JsonResponse({'success': False, 'msg': 'ID does not exist', 'code': 404}, status=400)


@login_required_401
def fetch_import_waiting(request):
    user_items = ImportQueue.objects.filter(user=request.user, imported=False).values('id', 'geofeatures', 'original_filename', 'raw_kml_hash', 'log_id', 'timestamp', 'imported')
    data = json.loads(json.dumps(list(user_items), cls=DjangoJSONEncoder))
    lock_manager = DBLockManager()
    for i, item in enumerate(data):
        count = len(item['geofeatures'])
        logs = _get_logs_by_log_id(str(item['log_id']))
        item['processing'] = not (len(item['geofeatures']) and len(logs)) and lock_manager.is_locked('data_importqueue', item['id'])
        item['feature_count'] = count

        # TODO: why is this here?
        # del item['geofeatures']
        # del item['log_id']  # Remove log_id from response as it's not needed by frontend
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
@csrf_protect  # TODO: put this on all routes
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
@csrf_protect  # TODO: put this on all routes
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
        feature = FeatureStore.objects.create(geojson=json.loads(c(**feature).model_dump_json()), source=import_item, user=request.user)
        feature.save()
        i += 1

    # Erase some unneded data since it's not needed anymore now that it's in the feature store.
    import_item.geofeatures = []
    import_item.log_id = None

    # Delete logs associated with this import since it's now successfully imported.
    _delete_logs_by_log_id(str(import_item.log_id))

    import_item.save()

    return JsonResponse({'success': True, 'msg': f'Successfully imported {i} items'})


def _hash_kml(b: str):
    if not isinstance(b, bytes):
        b = b.encode()
    return hashlib.sha256(b).hexdigest()
