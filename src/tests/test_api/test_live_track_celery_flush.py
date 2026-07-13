import json
from fnmatch import fnmatch
from unittest.mock import patch

import pytest
import redis
from django.contrib.auth import get_user_model

from extensions.live_track.src.backend.helpers import (
    LIVE_TRACK_FLUSH_TASK_NAME,
    flush_pending_broadcasts,
    queue_broadcast_track_updated,
)
from extensions.live_track.src.backend.models import LiveTrack, LiveTrackSubscription
from website.celery_app import celery_app

User = get_user_model()


class TestLiveTrackFlushTaskRegistration:
    def test_flush_task_is_registered_with_celery(self):
        """`LiveTrackConfig.extension_ready()` registers the flush task via `register_bg_task`
        at real Django startup - confirm that results in real Celery registration, with the
        hardening options (time_limit/soft_time_limit/autoretry_for) applied."""
        task = celery_app.tasks.get(LIVE_TRACK_FLUSH_TASK_NAME)
        assert task is not None
        assert task.soft_time_limit
        assert task.time_limit
        assert redis.exceptions.ConnectionError in task.autoretry_for
        assert redis.exceptions.TimeoutError in task.autoretry_for


class FakeRedis:
    def __init__(self):
        self._kv = {}
        self._lists = {}

    def set(self, key, value, nx=False, ex=None):
        if nx and key in self._kv:
            return False
        self._kv[key] = value
        return True

    def get(self, key):
        return self._kv.get(key)

    def delete(self, *keys):
        deleted = 0
        for key in keys:
            if key in self._kv:
                del self._kv[key]
                deleted += 1
            if key in self._lists:
                del self._lists[key]
                deleted += 1
        return deleted

    def rpush(self, key, value):
        self._lists.setdefault(key, []).append(value)
        return len(self._lists[key])

    def lrange(self, key, start, end):
        items = self._lists.get(key, [])
        if end == -1:
            return items[start:]
        return items[start : end + 1]

    def keys(self, pattern):
        all_keys = list(self._kv.keys()) + list(self._lists.keys())
        return [k for k in all_keys if fnmatch(k, pattern)]


class FakeChannelLayer:
    def __init__(self):
        self.messages = []

    def group_send(self, group, message):
        self.messages.append((group, message))


@pytest.mark.django_db
class TestLiveTrackCeleryFlush:
    def test_ingress_queue_debounces_flush_scheduling(self):
        owner = User.objects.create_user("owner@example.com", "password")
        track = LiveTrack.objects.create(
            tracker_secret="secret-1",
            name="Tracker One",
            user=owner,
            visibility="private",
            share_params_with_recipients=False,
            geometry={"type": "LineString", "coordinates": []},
            point_params=[],
        )
        redis = FakeRedis()

        with patch(
            "extensions.live_track.src.backend.helpers.get_redis_connection",
            return_value=redis,
        ), patch(
            "website.celery_app.celery_app.send_task"
        ) as send_task_mock:
            queued_first = queue_broadcast_track_updated(track, [1.0, 2.0, 3], {"ser": "abc"})
            queued_second = queue_broadcast_track_updated(track, [4.0, 5.0, 6], {"ser": "def"})

        assert queued_first is True
        assert queued_second is True
        assert send_task_mock.call_count == 1

    def test_flush_batches_updates_and_filters_subscriber_props(self):
        owner = User.objects.create_user("owner2@example.com", "password")
        subscriber = User.objects.create_user("subscriber@example.com", "password")
        track = LiveTrack.objects.create(
            tracker_secret="secret-2",
            name="Tracker Two",
            user=owner,
            visibility="private",
            share_params_with_recipients=False,
            geometry={"type": "LineString", "coordinates": []},
            point_params=[],
        )
        LiveTrackSubscription.objects.create(user=subscriber, track=track)

        redis = FakeRedis()
        fake_layer = FakeChannelLayer()

        payload_1 = {
            "track_id": str(track.id),
            "owner_id": owner.id,
            "subscriber_ids": [subscriber.id],
            "share_params_with_recipients": False,
            "point": [1.0, 2.0, 3],
            "props": {"ser": "abc", "acc": 1.0},
            "index": 0,
        }
        payload_2 = {
            "track_id": str(track.id),
            "owner_id": owner.id,
            "subscriber_ids": [subscriber.id],
            "share_params_with_recipients": False,
            "point": [4.0, 5.0, 6],
            "props": {"ser": "def", "acc": 2.0},
            "index": 1,
        }
        redis.rpush(f"live_track_pending:{track.id}", json.dumps(payload_1))
        redis.rpush(f"live_track_pending:{track.id}", json.dumps(payload_2))

        with patch(
            "extensions.live_track.src.backend.helpers.get_redis_connection",
            return_value=redis,
        ), patch(
            "extensions.live_track.src.backend.helpers.get_channel_layer",
            return_value=fake_layer,
        ), patch(
            "extensions.live_track.src.backend.helpers.async_to_sync",
            side_effect=lambda func: func,
        ):
            flushed = flush_pending_broadcasts()

        assert flushed == 1
        assert len(fake_layer.messages) == 2

        owner_msg = next(msg for group, msg in fake_layer.messages if group == f"live_track_{owner.id}")
        sub_msg = next(msg for group, msg in fake_layer.messages if group == f"live_track_{subscriber.id}")
        assert len(owner_msg["data"]["updates"]) == 2
        assert len(sub_msg["data"]["updates"]) == 2
        assert sub_msg["data"]["updates"][0]["props"] == {}
