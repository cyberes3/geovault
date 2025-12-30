"""
Tests for job recovery functionality.

Tests that interrupted jobs are correctly identified and re-enqueued.
"""

import pytest
from unittest.mock import patch, MagicMock

from api.models import ImportQueue
from geo_lib.processing.job_recovery import (
    recover_interrupted_jobs,
    get_interrupted_jobs_count,
    _reenqueue_job
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

    @patch('geo_lib.processing.job_recovery.start_worker_for_user')
    @patch('geo_lib.processing.job_recovery.get_processing_queue')
    @patch('geo_lib.processing.job_recovery.status_tracker')
    def test_recover_single_job(self, mock_tracker, mock_queue, mock_worker, user):
        """Test recovering a single interrupted job."""
        # Create an interrupted job
        job = ImportQueue.objects.create(
            user=user,
            original_filename='test.gpx',
            raw_file='<gpx>test content</gpx>',
            geofeatures=[],
            imported=False,
            unparsable=False
        )
        
        # Mock the status tracker
        mock_tracker.create_job.return_value = 'test-job-id'
        mock_tracker.set_job_result.return_value = None
        
        # Mock the queue
        mock_queue_instance = MagicMock()
        mock_queue_instance.enqueue.return_value = True
        mock_queue.return_value = mock_queue_instance
        
        # Recover jobs
        result = recover_interrupted_jobs()
        
        assert result['total_found'] == 1
        assert result['recovered'] == 1
        assert result['failed'] == 0
        assert result['users_affected'] == 1
        
        # Verify that job was created and enqueued
        mock_tracker.create_job.assert_called_once_with('test.gpx', user.id)
        mock_tracker.set_job_result.assert_called_once_with('test-job-id', {}, job.id)
        mock_queue_instance.enqueue.assert_called_once()
        mock_worker.assert_called_once()
        
        # Verify the job_data structure (file_data is NOT in Redis - it's in the database)
        enqueue_call = mock_queue_instance.enqueue.call_args[0][0]
        assert enqueue_call['job_id'] == 'test-job-id'
        assert enqueue_call['import_queue_id'] == job.id
        assert enqueue_call['filename'] == 'test.gpx'
        assert enqueue_call['user_id'] == user.id
        assert 'file_data' not in enqueue_call  # File data is in database, not Redis
        assert enqueue_call['replacement_feature_id'] is None

    @patch('geo_lib.processing.job_recovery.start_worker_for_user')
    @patch('geo_lib.processing.job_recovery.get_processing_queue')
    @patch('geo_lib.processing.job_recovery.status_tracker')
    def test_recover_multiple_jobs(self, mock_tracker, mock_queue, mock_worker, user, django_user_model):
        """Test recovering multiple interrupted jobs for different users."""
        # Create a second user
        another_user = django_user_model.objects.create_user(
            username='testuser2',
            email='testuser2@example.com',
            password='testpass123'
        )
        
        # Create interrupted jobs for two users
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
        
        # Mock the status tracker
        mock_tracker.create_job.side_effect = ['job-1', 'job-2']
        mock_tracker.set_job_result.return_value = None
        
        # Mock the queue
        mock_queue_instance = MagicMock()
        mock_queue_instance.enqueue.return_value = True
        mock_queue.return_value = mock_queue_instance
        
        # Recover jobs
        result = recover_interrupted_jobs()
        
        assert result['total_found'] == 2
        assert result['recovered'] == 2
        assert result['failed'] == 0
        assert result['users_affected'] == 2
        
        # Verify that jobs were created and enqueued
        assert mock_tracker.create_job.call_count == 2
        assert mock_queue_instance.enqueue.call_count == 2
        assert mock_worker.call_count == 2
        
        # Verify both jobs have correct data
        enqueue_calls = [call[0][0] for call in mock_queue_instance.enqueue.call_args_list]
        assert len(enqueue_calls) == 2
        # Check that file_data is NOT in Redis (it's in the database)
        assert all('file_data' not in call for call in enqueue_calls)
        # Check that import_queue_ids are set (used to read file_data from database)
        assert all('import_queue_id' in call and call['import_queue_id'] for call in enqueue_calls)

    @patch('geo_lib.processing.job_recovery.start_worker_for_user')
    @patch('geo_lib.processing.job_recovery.get_processing_queue')
    @patch('geo_lib.processing.job_recovery.status_tracker')
    def test_recover_with_replacement(self, mock_tracker, mock_queue, mock_worker, user, feature_store):
        """Test recovering a job that was a replacement upload."""
        # Create an interrupted replacement job
        job = ImportQueue.objects.create(
            user=user,
            original_filename='replacement.gpx',
            raw_file='<gpx>test content</gpx>',
            geofeatures=[],
            imported=False,
            unparsable=False,
            replacement=feature_store.id
        )
        
        # Mock the status tracker
        mock_tracker.create_job.return_value = 'test-job-id'
        mock_tracker.set_job_result.return_value = None
        
        # Mock the queue
        mock_queue_instance = MagicMock()
        mock_queue_instance.enqueue.return_value = True
        mock_queue.return_value = mock_queue_instance
        
        # Recover jobs
        result = recover_interrupted_jobs()
        
        assert result['total_found'] == 1
        assert result['recovered'] == 1
        
        # Verify that the replacement feature ID was preserved
        enqueue_call = mock_queue_instance.enqueue.call_args[0][0]
        assert enqueue_call['replacement_feature_id'] == feature_store.id
        assert enqueue_call['import_queue_id'] == job.id
        assert 'file_data' not in enqueue_call  # File data is in database, not Redis

    @patch('geo_lib.processing.job_recovery.get_processing_queue')
    @patch('geo_lib.processing.job_recovery.status_tracker')
    def test_recovery_handles_enqueue_failure(self, mock_tracker, mock_queue, user):
        """Test that recovery handles enqueue failures gracefully."""
        # Create an interrupted job
        ImportQueue.objects.create(
            user=user,
            original_filename='test.gpx',
            raw_file='<gpx>test content</gpx>',
            geofeatures=[],
            imported=False,
            unparsable=False
        )
        
        # Mock the status tracker
        mock_tracker.create_job.return_value = 'test-job-id'
        mock_tracker.set_job_result.return_value = None
        
        # Mock the queue to fail enqueue
        mock_queue_instance = MagicMock()
        mock_queue_instance.enqueue.return_value = False
        mock_queue.return_value = mock_queue_instance
        
        # Recover jobs
        result = recover_interrupted_jobs()
        
        assert result['total_found'] == 1
        assert result['recovered'] == 0
        assert result['failed'] == 1

