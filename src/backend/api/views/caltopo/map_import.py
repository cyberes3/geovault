"""
CalTopo map import endpoint.
"""
import json
from typing import Dict, Any
from django.http import HttpRequest, JsonResponse
from django.views.decorators.http import require_http_methods
from pydantic import BaseModel, Field, ConfigDict

from api.models import CalTopoUser, ImportQueue
from api.utils.rate_limit import caltopo_rate_limit
from api.utils.responses import error_response, success_response
from api.utils.caltopo_helpers import require_caltopo_connection
from api.validation.feature_updates import validate_payload
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.processing.jobs.process_job import ProcessJob
from geo_lib.services.caltopo_service import get_map_features, convert_caltopo_to_geojson
from geo_lib.website.auth import api_or_login_required_401

# Create singleton instance
process_job = ProcessJob(status_tracker)


class CalTopoMapImportPayload(BaseModel):
    """Pydantic model for map import request."""
    model_config = ConfigDict(extra='forbid')

    map_id: str = Field(description="CalTopo map ID")


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(CalTopoMapImportPayload)
@caltopo_rate_limit('import_map')
def import_caltopo_map(request: HttpRequest, validated_data: Dict[str, Any]) -> JsonResponse:
    """
    Import all features from a CalTopo map.
    
    POST /api/caltopo/import/map/
    Body: {
        "map_id": "abc12"
    }
    """
    map_id = validated_data['map_id']
    
    caltopo_user, error_resp = require_caltopo_connection(request)
    if error_resp:
        return error_resp
    
    # Get features from CalTopo map
    caltopo_features = get_map_features(request.user, map_id)
    if caltopo_features is None:
        return error_response(f'Map {map_id} not found or access denied', code=404)
    
    if not caltopo_features:
        return error_response(f'Map {map_id} has no features to import', code=400)
    
    # Check if this map is already in the import queue (atomic check)
    # Use transaction to prevent race conditions if two requests happen simultaneously
    filename = f'caltopo_map_{map_id}.geojson'
    from django.db import transaction
    
    with transaction.atomic():
        existing_queue_item = ImportQueue.objects.filter(
            user=request.user,
            original_filename=filename,
            imported=False,
            unparsable=False
        ).first()
        
        if existing_queue_item:
            return error_response(
                f'Map {map_id} is already in the import queue. Please complete the import.',
                code=409
            )
    
    # Delete existing features for this map
    # This handles cases where:
    # 1. Features were previously imported and still exist (normal re-import)
    # 2. Features were previously imported but user deleted some (clean up stale mappings)
    # 3. Features were previously imported but user edited some (delete old versions, import fresh)
    if map_id in caltopo_user.imported_features:
        existing_feature_ids = list(caltopo_user.imported_features[map_id].values())
        if existing_feature_ids:
            from api.models import FeatureStore
            # Delete only features that still exist (some may have been deleted by user)
            FeatureStore.objects.filter(id__in=existing_feature_ids, user=request.user).delete()
        # Always clear the mapping, even if some features were already deleted
        caltopo_user.imported_features[map_id] = {}
        caltopo_user.save()
    
    # Convert all features to GeoJSON
    geojson_features = [
        convert_caltopo_to_geojson(feature, map_id=map_id)
        for feature in caltopo_features
        if convert_caltopo_to_geojson(feature, map_id=map_id)
    ]
    
    if not geojson_features:
        # Log detailed error internally
        from geo_lib.logging.console import get_tagged_logger
        _logger = get_tagged_logger('CalTopoMapImport')
        _logger.warning(f'No valid features could be converted from CalTopo map {map_id}')
        # Return generic error message to user
        return error_response('No valid features could be processed from this CalTopo map. The map may contain only unsupported feature types.', code=400)
    
    # Create GeoJSON FeatureCollection
    feature_collection = {'type': 'FeatureCollection', 'features': geojson_features}
    geojson_string = json.dumps(feature_collection)
    
    # Create and enqueue processing job
    # ProcessJob will create the ImportQueue entry automatically
    job_id = status_tracker.create_job(f'caltopo_map_{map_id}.geojson', request.user.id)
    process_job.enqueue_job(
        job_id,
        geojson_string.encode('utf-8'),
        f'caltopo_map_{map_id}.geojson',
        request.user.id,
        replacement_feature_id=None
    )
    
    # Get the import_queue_id from the job (ProcessJob creates it)
    job = status_tracker.get_job(job_id)
    import_queue_id = job.import_queue_id if job else None
    
    return success_response({
        'msg': f'Map import queued. Processing {len(geojson_features)} features.',
        'job_id': job_id,
        'import_queue_id': import_queue_id,
        'feature_count': len(geojson_features)
    })

