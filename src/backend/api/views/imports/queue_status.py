"""Import job status endpoints."""

from api.utils.responses import error_response, not_found_response, success_response
from geo_lib.processing.jobs.helpers.redis_job_storage import get_user_jobs
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from website.auth_decorators import api_or_login_required_401


@api_or_login_required_401()
def get_processing_status(request, job_id):
    if not job_id:
        return error_response('Job ID not provided', code=400)

    job_status = status_tracker.get_job_status(job_id)

    if not job_status:
        return not_found_response('Job not found')

    job = status_tracker.get_job(job_id)
    if not job or job.user_id != request.user.id:
        return not_found_response('Job not found')

    return success_response({'job_status': job_status})


@api_or_login_required_401()
def get_user_processing_jobs(request):
    user_jobs = status_tracker.get_user_jobs(request.user.id)

    job_statuses = []
    for job in user_jobs:
        job_status = status_tracker.get_job_status(job.job_id)
        if job_status:
            job_statuses.append(job_status)

    return success_response({'jobs': job_statuses})


@api_or_login_required_401()
def get_all_job_statuses(request):
    jobs = get_user_jobs(request.user.id)
    return success_response({'jobs': jobs})
