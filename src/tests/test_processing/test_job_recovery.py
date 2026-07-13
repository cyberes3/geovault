"""
Tests for job recovery functionality.

Tests that interrupted jobs are correctly identified and redispatched to the `imports` Celery
queue. `dispatch_import_job` itself is mocked throughout so these tests exercise only the
recovery bookkeeping (which rows qualify, what job_data gets built), not real file processing.
"""

import pytest
from unittest.mock import patch, MagicMock

from api.models import ImportQueue
from geo_lib.processing.job_recovery import (
    recover_interrupted_jobs,
    get_interrupted_jobs_count,
    _redispatch_job,
)


@pytest.mark.django_db
class TestJobRecovery:
    """Test job recovery functionality."""

    def test_no_interrupted_jobs(self, user):
        """Test that recovery handles no interrupted jobs gracefully."""
        result = recover_interrupted_jobs()
        
        assert result['total_found'] == 0
        assert result['recovered'] == 0
        assert result['failed'] == 0
        assert result['users_affected'] == 0

    def test_identify_interrupted_job(self, user):
        """Test that interrupted jobs are correctly identified."""
        # Create an interrupted job (has raw_file but no geofeatures)
        ImportQueue.objects.create(
            user=user,
            original_filename='test.gpx',
            raw_file='<gpx>test content</gpx>',
            geofeatures=[],
            imported=False,
            unparsable=False
        )
        
        count = get_interrupted_jobs_count()
        assert count == 1

    def test_ignore_completed_jobs(self, user):
        """Test that completed jobs are not identified as interrupted."""
        # Create a completed job (has geofeatures)
        ImportQueue.objects.create(
            user=user,
            original_filename='completed.gpx',
            raw_file='<gpx>test content</gpx>',
            geofeatures=[{'type': 'Feature', 'properties': {}, 'geometry': {}}],
            imported=False,
            unparsable=False
        )
        
        count = get_interrupted_jobs_count()
        assert count == 0

    def test_ignore_unparsable_jobs(self, user):
        """Test that unparsable jobs are not recovered."""
        # Create an unparsable job
        ImportQueue.objects.create(
            user=user,
            original_filename='unparsable.gpx',
            raw_file='<gpx>test content</gpx>',
            geofeatures=[],
            imported=False,
            unparsable=True
        )
        
        count = get_interrupted_jobs_count()
        assert count == 0

    def test_ignore_imported_jobs(self, user):
        """Test that already imported jobs are not recovered."""
        # Create an imported job
        ImportQueue.objects.create(
            user=user,
            original_filename='imported.gpx',
            raw_file='<gpx>test content</gpx>',
            geofeatures=[],
            imported=True,
            unparsable=False
        )
        
        count = get_interrupted_jobs_count()
        assert count == 0

    def test_ignore_empty_raw_file(self, user):
        """Test that jobs with empty raw_file are not recovered."""
        # Create a job with empty raw_file
        ImportQueue.objects.create(
            user=user,
            original_filename='empty.gpx',
            raw_file='',
            geofeatures=[],
            imported=False,
            unparsable=False
        )
        
        count = get_interrupted_jobs_count()
        assert count == 0

    @patch('geo_lib.processing.job_recovery.dispatch_import_job')
    def test_recover_single_job(self, mock_dispatch, user):
        """Test recovering a single interrupted job."""
        job = ImportQueue.objects.create(
            user=user,
            original_filename='test.gpx',
            raw_file='<gpx>test content</gpx>',
            geofeatures=[],
            imported=False,
            unparsable=False
        )

        result = recover_interrupted_jobs()
        
        assert result['total_found'] == 1
        assert result['recovered'] == 1
        assert result['failed'] == 0
        assert result['users_affected'] == 1

        mock_dispatch.assert_called_once()
        dispatched_job_id, job_data = mock_dispatch.call_args[0]

        # Verify the job_data structure (file_data is NOT included - it's read from the
        # database by the task itself using import_queue_id).
        assert job_data['job_id'] == dispatched_job_id
        assert job_data['import_queue_id'] == job.id
        assert job_data['filename'] == 'test.gpx'
        assert job_data['user_id'] == user.id
        assert 'file_data' not in job_data
        assert job_data['replacement_feature_id'] is None
        assert job_data['job_ceiling_seconds'] > 0

    @patch('geo_lib.processing.job_recovery.dispatch_import_job')
    def test_recover_multiple_jobs(self, mock_dispatch, user, django_user_model):
        """Test recovering multiple interrupted jobs for different users."""
        another_user = django_user_model.objects.create_user(
            username='testuser2',
            email='testuser2@example.com',
            password='testpass123'
        )
        
        ImportQueue.objects.create(
            user=user,
            original_filename='test1.gpx',
            raw_file='<gpx>test content 1</gpx>',
            geofeatures=[],
            imported=False,
            unparsable=False
        )
        
        ImportQueue.objects.create(
            user=another_user,
            original_filename='test2.gpx',
            raw_file='<gpx>test content 2</gpx>',
            geofeatures=[],
            imported=False,
            unparsable=False
        )

        result = recover_interrupted_jobs()
        
        assert result['total_found'] == 2
        assert result['recovered'] == 2
        assert result['failed'] == 0
        assert result['users_affected'] == 2

        assert mock_dispatch.call_count == 2
        dispatched_job_data = [call[0][1] for call in mock_dispatch.call_args_list]
        assert all('file_data' not in job_data for job_data in dispatched_job_data)
        assert all(job_data.get('import_queue_id') for job_data in dispatched_job_data)

    @patch('geo_lib.processing.job_recovery.dispatch_import_job')
    def test_recover_with_replacement(self, mock_dispatch, user, feature_store):
        """Test recovering a job that was a replacement upload."""
        job = ImportQueue.objects.create(
            user=user,
            original_filename='replacement.gpx',
            raw_file='<gpx>test content</gpx>',
            geofeatures=[],
            imported=False,
            unparsable=False,
            replacement=feature_store.id
        )

        result = recover_interrupted_jobs()
        
        assert result['total_found'] == 1
        assert result['recovered'] == 1

        _, job_data = mock_dispatch.call_args[0]
        assert job_data['replacement_feature_id'] == feature_store.id
        assert job_data['import_queue_id'] == job.id
        assert 'file_data' not in job_data

    def test_recovery_skips_row_already_locked_by_another_process(self, user):
        """
        If another process already holds the per-row recovery-dispatch lock (e.g. a concurrent
        recovery run), this row must be skipped rather than dispatched twice.
        """
        job = ImportQueue.objects.create(
            user=user,
            original_filename='test.gpx',
            raw_file='<gpx>test content</gpx>',
            geofeatures=[],
            imported=False,
            unparsable=False
        )

        with patch('geo_lib.processing.job_recovery.try_acquire_lock', return_value=None):
            with patch('geo_lib.processing.job_recovery.dispatch_import_job') as mock_dispatch:
                recovered = _redispatch_job(job)

        assert recovered is False
        mock_dispatch.assert_not_called()

    def test_recovery_skips_job_with_no_raw_file(self, user):
        """A row with no raw_file can't be recovered, even if it slipped through the queryset."""
        job = MagicMock(id=1, raw_file='', original_filename='test.gpx', user_id=user.id)

        with patch('geo_lib.processing.job_recovery.dispatch_import_job') as mock_dispatch:
            recovered = _redispatch_job(job)

        assert recovered is False
        mock_dispatch.assert_not_called()
