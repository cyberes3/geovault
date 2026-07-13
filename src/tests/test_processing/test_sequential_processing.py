"""
Tests for per-user serialization of import/processing jobs.

Sequential processing is now enforced by a Redis lock keyed per-user (see
`geo_lib.processing.jobs.process_job.job.ProcessJob.process_locked`) rather than an in-process
worker thread, so these tests exercise the lock directly instead of racing real background
threads against `time.sleep()`.
"""

from unittest.mock import patch

import pytest
from celery.exceptions import Retry
from django.contrib.auth import get_user_model
from django.test import TransactionTestCase

from api.tasks import IMPORT_LOCK_RETRY_COUNTDOWN_SECONDS, process_import_job
from geo_lib.processing.jobs.process_job.dispatch import ImportLockContention
from geo_lib.processing.jobs.process_job.job import ProcessJob
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.utils.redis_locks import try_acquire_lock

User = get_user_model()


def _make_job_data(user_id: int, filename: str = 'test.kml') -> dict:
    job_id = status_tracker.create_job(filename, user_id)
    job_data = {
        'job_id': job_id,
        'import_queue_id': 1,
        'filename': filename,
        'user_id': user_id,
        'timestamp': 0.0,
        'replacement_feature_id': None,
        'job_ceiling_seconds': 300,
    }
    return job_id, job_data


class TestPerUserProcessingLock(TransactionTestCase):
    """Test that ProcessJob.process_locked serializes processing per-user via a Redis lock."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='sequential1@example.com',
            password='testpass123',
            username='sequential_user1',
        )
        self.other_user = User.objects.create_user(
            email='sequential2@example.com',
            password='testpass123',
            username='sequential_user2',
        )
        self.process_job = ProcessJob(status_tracker)

    def test_process_locked_runs_execute_job_when_lock_is_free(self):
        job_id, job_data = _make_job_data(self.user.id)

        with patch.object(self.process_job, '_execute_job') as mock_execute:
            self.process_job.process_locked(job_id, job_data)

        mock_execute.assert_called_once_with(job_id, job_data)

    def test_process_locked_raises_contention_when_user_already_locked(self):
        job_id, job_data = _make_job_data(self.user.id)

        held_lock = try_acquire_lock(f"import_processing_lock:user:{self.user.id}", timeout_seconds=60)
        self.assertIsNotNone(held_lock, "Precondition: should be able to acquire the lock manually")

        try:
            with patch.object(self.process_job, '_execute_job') as mock_execute:
                with self.assertRaises(ImportLockContention):
                    self.process_job.process_locked(job_id, job_data)
            mock_execute.assert_not_called()
        finally:
            held_lock.release()

    def test_process_locked_releases_lock_after_success(self):
        job_id, job_data = _make_job_data(self.user.id)

        with patch.object(self.process_job, '_execute_job'):
            self.process_job.process_locked(job_id, job_data)

        # Lock should be free again, so a fresh acquire should succeed immediately.
        lock = try_acquire_lock(f"import_processing_lock:user:{self.user.id}", timeout_seconds=60)
        self.assertIsNotNone(lock, "Lock should have been released after process_locked returned")
        lock.release()

    def test_process_locked_releases_lock_even_if_execute_job_raises(self):
        job_id, job_data = _make_job_data(self.user.id)

        with patch.object(self.process_job, '_execute_job', side_effect=RuntimeError("boom")):
            with self.assertRaises(RuntimeError):
                self.process_job.process_locked(job_id, job_data)

        lock = try_acquire_lock(f"import_processing_lock:user:{self.user.id}", timeout_seconds=60)
        self.assertIsNotNone(lock, "Lock should be released even when _execute_job raises")
        lock.release()

    def test_process_locked_skips_canceled_job(self):
        job_id, job_data = _make_job_data(self.user.id)
        status_tracker.cancel_job(job_id)

        with patch.object(self.process_job, '_execute_job') as mock_execute:
            self.process_job.process_locked(job_id, job_data)

        mock_execute.assert_not_called()

    def test_different_users_do_not_contend_for_the_same_lock(self):
        """Two different users' jobs must be able to process concurrently."""
        job_id_a, job_data_a = _make_job_data(self.user.id)
        job_id_b, job_data_b = _make_job_data(self.other_user.id)

        held_lock = try_acquire_lock(f"import_processing_lock:user:{self.user.id}", timeout_seconds=60)
        self.assertIsNotNone(held_lock)

        try:
            # User A is locked, but user B's job should be unaffected.
            with patch.object(self.process_job, '_execute_job') as mock_execute:
                self.process_job.process_locked(job_id_b, job_data_b)
            mock_execute.assert_called_once_with(job_id_b, job_data_b)
        finally:
            held_lock.release()


class TestImportTaskLockContentionRetry(TransactionTestCase):
    """Test that the Celery task wrapper retries (rather than fails) on lock contention."""

    def setUp(self):
        self.user = User.objects.create_user(
            email='sequential3@example.com',
            password='testpass123',
            username='sequential_user3',
        )

    def test_task_retries_with_expected_countdown_on_contention(self):
        _, job_data = _make_job_data(self.user.id)

        held_lock = try_acquire_lock(f"import_processing_lock:user:{self.user.id}", timeout_seconds=60)
        self.assertIsNotNone(held_lock)

        try:
            with patch.object(process_import_job, 'retry', side_effect=Retry) as mock_retry:
                with pytest.raises(Retry):
                    process_import_job.run(job_data['job_id'], job_data)
            mock_retry.assert_called_once_with(
                countdown=IMPORT_LOCK_RETRY_COUNTDOWN_SECONDS, max_retries=None,
            )
        finally:
            held_lock.release()
