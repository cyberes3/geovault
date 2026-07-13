"""Import upload endpoint"""
from django import forms
from django.http import Http404
from django.views.decorators.http import require_http_methods

from api.services.feature_service import FeatureService
from api.utils.responses import error_response, success_response
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.processing.jobs.process_job import ProcessJob
from geo_lib.security.SecureFileValidator import basic_file_security_check
from geo_lib.security.rate_limit import RedisRateLimiter
from geo_lib.utils.secure_path import secure_filename
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger()

# Create singleton instance
process_job = ProcessJob(status_tracker)

_upload_item_rate_limiter = RedisRateLimiter(name='upload_item', limit=30, window_seconds=60.0)


class DocumentForm(forms.Form):
    file = forms.FileField()


@api_or_login_required_401()
@_upload_item_rate_limiter()
@require_http_methods(["POST"])
def upload_item(request):
    """
    Main upload endpoint - now uses async processing by default.
    """
    form = DocumentForm(request.POST, request.FILES)
    if form.is_valid():
        uploaded_file = request.FILES['file']
        file_name = secure_filename(uploaded_file.name)
        if not file_name:
            ext = uploaded_file.name.rsplit(".", 1)[-1].lower() if "." in uploaded_file.name else ""
            file_name = f"upload.{ext}" if ext else "upload"

        # Basic security checks for quick rejection (full validation happens in async processing)
        is_valid, validation_message = basic_file_security_check(uploaded_file)

        if not is_valid:
            _logger.warning(f"Basic security check failed for {file_name}: {validation_message}")
            return error_response(
                f'File validation failed: {validation_message}',
                code=400,
                details={'job_id': None}
            )

        # Read file data after basic security check
        file_data = uploaded_file.read()

        # Get optional replacement parameter (feature ID being updated)
        replacement_feature_id = None
        if 'replacement' in request.POST:
            try:
                replacement_feature_id = int(request.POST['replacement'])
            except (ValueError, TypeError):
                return error_response(
                    'Invalid replacement feature ID',
                    code=400,
                    details={'job_id': None}
                )
            # Verify ownership up front so a guessed/foreign feature ID never even reaches the
            # ImportQueue row; the geometry-apply step re-checks this too, but that's defense in
            # depth, not the primary gate.
            try:
                FeatureService.get_owned_feature_or_404(request.user, replacement_feature_id)
            except Http404:
                return error_response(
                    'Replacement feature not found or access denied',
                    code=404,
                    details={'job_id': None}
                )

        # Create a processing job
        job_id = status_tracker.create_job(file_name, request.user.id)

        # Enqueue job to Redis queue for sequential processing
        process_job.enqueue_job(job_id, file_data, file_name, request.user.id, replacement_feature_id=replacement_feature_id)
        return success_response({
            'msg': 'File uploaded successfully, processing queued',
            'job_id': job_id
        })
    else:
        # Try to get filename even if form validation failed
        filename = "unknown file"
        if 'file' in request.FILES:
            filename = secure_filename(request.FILES['file'].name) or "unknown file"
        return error_response(
            f'Invalid upload structure for file "{filename}"',
            code=400,
            details={'job_id': None}
        )
