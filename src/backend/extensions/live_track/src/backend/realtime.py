"""TrackRealtime: updates[] only, SCAN, delete-after-ack, ACL audience."""

import json
import time

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer

from geo_lib.track.store import InsertedPoint
from geo_lib.utils.redis_connection import get_redis_connection
from geo_lib.utils.redis_scan import scan_keys
from website.celery_app import celery_app

from .access import accepted_group_viewer_ids_for_track
from .models import LiveTrack, LiveTrackSubscription


LIVE_TRACK_PENDING_PREFIX = "live_track_pending:"
LIVE_TRACK_REVISION_PREFIX = "live_track_revision:"
LIVE_TRACK_FLUSHER_ALIVE_KEY = "live_track_flusher_alive"
LIVE_TRACK_FLUSH_TASK_NAME = "extensions.live_track.flush_pending_broadcasts"
LIVE_TRACK_FLUSH_SCHEDULE_KEY = "live_track_flush_scheduled"
LIVE_TRACK_FLUSH_DELAY_SECONDS = 0.2


def _props_for_subscriber(props: dict, share_params: bool) -> dict:
    if not share_params:
        return {}
    out = dict(props) if props else {}
    out.pop("ser", None)
    return out


def _subscriber_ids_for_track(track: LiveTrack) -> list[int]:
    return list(
        LiveTrackSubscription.objects.filter(track=track)
        .exclude(user_id=track.user_id)
        .values_list("user_id", flat=True)
    )


def _audience_ids(track: LiveTrack, cached_subscriber_ids: list[int] | None) -> set[int]:
    ids = {track.user_id}
    ids.update(cached_subscriber_ids or [])
    ids.update(accepted_group_viewer_ids_for_track(track))
    ids.discard(None)
    return ids


def _next_revision(redis_client, track_id: str) -> int:
    return int(redis_client.incr(f"{LIVE_TRACK_REVISION_PREFIX}{track_id}"))


def _pending_item(track: LiveTrack, inserted: InsertedPoint, subscriber_ids: list[int]) -> dict:
    return {
        "track_id": str(track.id),
        "owner_id": track.user_id,
        "subscriber_ids": subscriber_ids,
        "share_params_with_recipients": getattr(track, "share_params_with_recipients", False),
        "point": inserted.point,
        "props": inserted.props or {},
        "index": inserted.index,
    }


def _send_updates(track_id: str, revision: int, updates_by_user: dict[int, list]) -> None:
    channel_layer = get_channel_layer()
    if not channel_layer:
        raise RuntimeError("channel layer unavailable")
    for user_id, updates in updates_by_user.items():
        data = {"track_id": track_id, "revision": revision, "updates": updates}
        message = {"type": "live_track_track_updated", "data": data}
        async_to_sync(channel_layer.group_send)(f"live_track_{user_id}", message)


def set_flusher_alive() -> None:
    try:
        redis_client = get_redis_connection()
        redis_client.set(LIVE_TRACK_FLUSHER_ALIVE_KEY, str(time.time()))
    except Exception:
        pass


def get_flusher_alive_timestamp() -> float | None:
    try:
        redis_client = get_redis_connection()
        raw = redis_client.get(LIVE_TRACK_FLUSHER_ALIVE_KEY)
        if raw is None:
            return None
        s = raw.decode() if isinstance(raw, bytes) else raw
        return float(s)
    except Exception:
        return None


def is_flusher_alive(max_age_seconds: float) -> bool:
    ts = get_flusher_alive_timestamp()
    if ts is None:
        return False
    return (time.time() - ts) <= max_age_seconds


def drop_pending(track_id) -> None:
    try:
        redis_client = get_redis_connection()
    except Exception:
        return
    redis_client.delete(f"{LIVE_TRACK_PENDING_PREFIX}{track_id}")


def _scan_pending_keys(redis_client) -> list:
    return scan_keys(redis_client, f"{LIVE_TRACK_PENDING_PREFIX}*")


def _schedule_live_track_flush(redis_client) -> None:
    lock_seconds = max(1, int(LIVE_TRACK_FLUSH_DELAY_SECONDS) + 1)
    acquired = redis_client.set(
        LIVE_TRACK_FLUSH_SCHEDULE_KEY,
        "1",
        nx=True,
        ex=lock_seconds,
    )
    if not acquired:
        return
    try:
        celery_app.send_task(
            LIVE_TRACK_FLUSH_TASK_NAME,
            queue="live_track",
            countdown=LIVE_TRACK_FLUSH_DELAY_SECONDS,
        )
    except Exception:
        redis_client.delete(LIVE_TRACK_FLUSH_SCHEDULE_KEY)
        raise


def queue_broadcast_track_updated(
    track: LiveTrack,
    point: list,
    props: dict,
    index: int | None = None,
) -> bool:
    inserted = InsertedPoint(index=index if index is not None else 0, point=point, props=props or {})
    return queue_inserted_points(track, [inserted])


def queue_inserted_points(track: LiveTrack, inserted: list[InsertedPoint]) -> bool:
    if not inserted:
        return True
    try:
        redis_client = get_redis_connection()
    except Exception:
        return False
    subscriber_ids = _subscriber_ids_for_track(track)
    key = f"{LIVE_TRACK_PENDING_PREFIX}{track.id}"
    try:
        for item in inserted:
            redis_client.rpush(key, json.dumps(_pending_item(track, item, subscriber_ids)))
        _schedule_live_track_flush(redis_client)
    except Exception:
        return False
    return True


def broadcast_inserted_points(track: LiveTrack, inserted: list[InsertedPoint]) -> None:
    """Immediate updates[] fanout when Redis queue is unavailable."""
    if not inserted:
        return
    share_params = getattr(track, "share_params_with_recipients", False)
    subscriber_ids = _subscriber_ids_for_track(track)
    audience = _audience_ids(track, subscriber_ids)
    owner_updates = [{"point": i.point, "props": i.props or {}, "index": i.index} for i in inserted]
    sub_updates = [
        {"point": i.point, "props": _props_for_subscriber(i.props or {}, share_params), "index": i.index}
        for i in inserted
    ]
    try:
        redis_client = get_redis_connection()
        revision = _next_revision(redis_client, str(track.id))
    except Exception:
        revision = int(time.time() * 1000)
    updates_by_user = {}
    for uid in audience:
        updates_by_user[uid] = owner_updates if uid == track.user_id else sub_updates
    _send_updates(str(track.id), revision, updates_by_user)


def broadcast_track_updated(
    track: LiveTrack,
    point: list,
    props: dict,
    index: int | None = None,
):
    inserted = InsertedPoint(index=index if index is not None else 0, point=point, props=props or {})
    broadcast_inserted_points(track, [inserted])


def flush_pending_broadcasts() -> int:
    try:
        redis_client = get_redis_connection()
    except Exception:
        return 0
    redis_client.delete(LIVE_TRACK_FLUSH_SCHEDULE_KEY)
    keys = _scan_pending_keys(redis_client)
    if not keys:
        return 0
    flushed = 0
    track_cache: dict[str, LiveTrack] = {}
    for key in keys:
        raw_list = redis_client.lrange(key, 0, -1)
        if not raw_list:
            redis_client.delete(key)
            continue
        key_str = key.decode() if isinstance(key, bytes) else key
        track_id = key_str.replace(LIVE_TRACK_PENDING_PREFIX, "", 1)
        updates_by_user: dict[int, list] = {}
        cached_subs: list[int] | None = None
        owner_id = None
        share_params = False
        for raw in raw_list:
            try:
                raw_str = raw.decode() if isinstance(raw, bytes) else raw
                item = json.loads(raw_str)
            except (json.JSONDecodeError, TypeError):
                continue
            owner_id = item.get("owner_id")
            cached_subs = item.get("subscriber_ids") or []
            share_params = item.get("share_params_with_recipients", False)
            point = item.get("point") or []
            props = item.get("props") or {}
            idx = item.get("index")
            owner_update = {"point": point, "props": props, "index": idx}
            sub_update = {"point": point, "props": _props_for_subscriber(props, share_params), "index": idx}
            if owner_id is not None:
                updates_by_user.setdefault(owner_id, []).append(owner_update)
            for uid in cached_subs:
                updates_by_user.setdefault(uid, []).append(sub_update)

        track = track_cache.get(track_id)
        if track is None:
            track = LiveTrack.objects.filter(id=track_id).first()
            if track is not None:
                track_cache[track_id] = track
        if track is not None:
            extra_ids = _audience_ids(track, cached_subs)
            for uid in extra_ids:
                if uid in updates_by_user:
                    continue
                if uid == track.user_id:
                    continue
                updates_by_user[uid] = [
                    {
                        "point": u["point"],
                        "props": _props_for_subscriber(u.get("props") or {}, share_params),
                        "index": u.get("index"),
                    }
                    for u in (updates_by_user.get(owner_id) or [])
                ]
        if not updates_by_user:
            redis_client.delete(key)
            continue
        revision = _next_revision(redis_client, track_id)
        try:
            _send_updates(track_id, revision, updates_by_user)
        except Exception:
            continue
        redis_client.delete(key)
        flushed += 1
    return flushed


def flush_pending_broadcasts_task() -> int:
    return flush_pending_broadcasts()
