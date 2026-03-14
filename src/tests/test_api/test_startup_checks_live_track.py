import pytest

from website.startup_checks import check_live_track_flusher


@pytest.mark.django_db
def test_live_track_flusher_startup_check_is_noop_after_celery_cutover():
    assert check_live_track_flusher() is True
