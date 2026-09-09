"""Import duplicate detection operations"""

from django.views.decorators.http import require_http_methods

from api.models import ImportDraftFeature, ImportQueue
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, success_response, handle_404
from api.validation.decorators import validate_payload
from api.validation.payloads.imports import RecheckDuplicatesPayload
from geo_lib.importing.jobs.dispatch import RECHECK_CELERY_TASK_NAME, dispatch_named_job
from geo_lib.importing.jobs.user_lock import is_user_import_lock_held
from geo_lib.importing.session import RawFile
from geo_lib.processing.job_ceiling import calculate_job_ceiling_seconds
from geo_lib.processing.jobs.helpers.status_tracker import JobType, status_tracker
from website.auth_decorators import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["POST"])
@handle_404
@validate_payload(RecheckDuplicatesPayload, allow_empty=True)
def recheck_duplicates(request, item_id, validated_data):
    """
    Enqueue a Celery recheck of draft-feature duplicates for an import queue item.
    """
    import_item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    if import_item.imported or import_item.queue_status == ImportQueue.STATUS_IMPORTED:
        return error_response(
            'Cannot recheck duplicates for items that have already been imported',
            code=400
        )

    if is_user_import_lock_held(request.user.id):
        return error_response(
            'Another import job is already processing for this user',
            code=409
        )

    if not ImportDraftFeature.objects.filter(queue=import_item).exists():
        return success_response({
            'msg': 'No features to check',
            'status': 'completed',
        })

    current_page = validated_data.get('page') or 1
    raw = RawFile.load(import_item.raw_file, import_item.raw_file_encoding, import_item.file_hash)
    ceiling_seconds = calculate_job_ceiling_seconds(len(raw.data))
    job_id = status_tracker.create_job(
        f"Recheck {import_item.original_filename}", request.user.id, JobType.RECHECK
    )
    status_tracker.set_job_import_queue_id(job_id, item_id)
    dispatch_named_job(
        RECHECK_CELERY_TASK_NAME,
        [job_id, item_id, request.user.id, current_page, ceiling_seconds],
        ceiling_seconds,
    )
    return success_response({
        'job_id': job_id,
        'status': 'queued',
        'msg': 'Duplicate recheck started',
    }, status=202)
