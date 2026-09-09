"""Single write path for Basic, Hauk, app-ingress, replay, and stream_test."""

from django.db import transaction
from django.utils import timezone

from geo_lib.track.store import InsertedPoint, PointStore

from .models import LiveTrack
from .realtime import broadcast_inserted_points, drop_pending, queue_inserted_points


class PointWriter:
    def append(
        self,
        track: LiveTrack,
        lat: float,
        lon: float,
        timestamp_ms: int,
        extra: dict | None = None,
    ) -> InsertedPoint | None:
        inserted = self.append_many(
            track,
            [{"lat": lat, "lon": lon, "timestamp": timestamp_ms, **(extra or {})}],
        )
        return inserted[0] if inserted else None

    def append_many(self, track: LiveTrack, points: list[dict]) -> list[InsertedPoint]:
        if not points:
            return []
        with transaction.atomic():
            locked = LiveTrack.objects.select_for_update().get(pk=track.id)
            store = PointStore.from_track(locked)
            inserted = store.append_many(points)
            if inserted:
                store.persist_appended(locked, inserted)
                locked.updated_at = timezone.now()
                locked.save(update_fields=["updated_at"])
        if not inserted:
            return []
        if not queue_inserted_points(track, inserted):
            broadcast_inserted_points(track, inserted)
        return inserted

    def clear_history(self, track: LiveTrack) -> LiveTrack:
        with transaction.atomic():
            locked = LiveTrack.objects.select_for_update().get(pk=track.id)
            store = PointStore.from_track(locked)
            store.trim_to_latest()
            store.persist_to_track(locked)
        drop_pending(locked.id)
        return locked


point_writer = PointWriter()


def append_point_to_track(
    track,
    lat: float,
    lon: float,
    timestamp_ms: int,
    extra: dict | None = None,
) -> int | None:
    inserted = point_writer.append(track, lat, lon, timestamp_ms, extra)
    return inserted.index if inserted else None
