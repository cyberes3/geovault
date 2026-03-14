from datetime import timedelta

import pytest
from django.contrib.auth import get_user_model
from django.utils import timezone

from api.models import ImportQueue
from api.services.replacement_cleanup_service import cleanup_orphaned_replacements

User = get_user_model()


@pytest.mark.django_db
class TestReplacementCleanupTask:
    def _create_import_row(self, user, *, replacement, imported, age_minutes):
        row = ImportQueue.objects.create(
            user=user,
            imported=imported,
            original_filename="test.kml",
            raw_file="<kml>test</kml>",
            file_hash=f"hash-{replacement}-{imported}-{age_minutes}",
            replacement=replacement,
        )
        ts = timezone.now() - timedelta(minutes=age_minutes)
        ImportQueue.objects.filter(pk=row.pk).update(timestamp=ts)
        row.refresh_from_db()
        return row

    def test_cleanup_only_deletes_old_unimported_replacements(self):
        user = User.objects.create_user("cleanup@example.com", "password")
        old_orphan = self._create_import_row(
            user, replacement=123, imported=False, age_minutes=15
        )
        self._create_import_row(user, replacement=124, imported=False, age_minutes=5)
        self._create_import_row(user, replacement=125, imported=True, age_minutes=20)
        self._create_import_row(user, replacement=None, imported=False, age_minutes=20)

        deleted_count = cleanup_orphaned_replacements()

        assert deleted_count == 1
        assert not ImportQueue.objects.filter(pk=old_orphan.pk).exists()
        assert ImportQueue.objects.count() == 3

    def test_cleanup_is_idempotent(self):
        user = User.objects.create_user("cleanup2@example.com", "password")
        self._create_import_row(user, replacement=999, imported=False, age_minutes=20)

        first = cleanup_orphaned_replacements()
        second = cleanup_orphaned_replacements()

        assert first == 1
        assert second == 0
