"""
Tests that database operations work when connection pooling is enabled.

Uses the dedicated pool_test DB alias (same gv_tests database, pooled connection).
Only test_database_pooling.py uses pool_test; all other tests use default (no pool).
Requires database.pool in config.yaml; tests are skipped otherwise.
"""
import pytest
from django.conf import settings
from django.contrib.auth import get_user_model

User = get_user_model()

POOL_TEST_ALIAS = "pool_test"


def _pool_test_available():
    return POOL_TEST_ALIAS in settings.DATABASES


@pytest.mark.django_db(databases=["default", POOL_TEST_ALIAS])
@pytest.mark.skipif(not _pool_test_available(), reason="Configure database.pool in config.yaml to run pooling tests")
class TestDatabasePooling:
    """Run basic DB operations via the dedicated pooled connection (pool_test alias)."""

    def test_operations_with_pooling(self):
        """Query and create with pool options enabled."""
        users = User.objects.using(POOL_TEST_ALIAS)
        n = users.count()
        User.objects.db_manager(POOL_TEST_ALIAS).create_user(
            email="pooltest@example.com",
            password="testpass",
            username="pooltestuser",
        )
        assert users.count() == n + 1

    def test_multiple_operations_use_pool(self):
        """Several operations; pool hands out connections as needed."""
        manager = User.objects.db_manager(POOL_TEST_ALIAS)
        for i in range(3):
            manager.create_user(
                email=f"poolmulti{i}@example.com",
                password="testpass",
                username=f"poolmulti{i}",
            )
        assert User.objects.using(POOL_TEST_ALIAS).filter(username__startswith="poolmulti").count() == 3
