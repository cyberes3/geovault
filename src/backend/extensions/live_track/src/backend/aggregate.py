"""TrackAggregate: metadata + credentials + sharing policy + PointStore."""

from geo_lib.sharing.access_policy import AccessRole
from geo_lib.track.payload import TrackPayloadMode, build_track_payload
from geo_lib.track.store import PointStore

from api.sharing.grants import ShareGrantService
from geo_lib.sharing.constants import KIND_LIVE_TRACK

from .access import TrackerAccessPolicy
from .models import LiveTrack


class TrackAggregate:
    def __init__(self, track: LiveTrack):
        self.track = track
        self._points: PointStore | None = None
        self.policy = TrackerAccessPolicy(track)

    @property
    def points(self) -> PointStore:
        if self._points is None:
            self._points = PointStore.from_track(self.track)
        return self._points

    def latest(self):
        return PointStore.latest_from_track(self.track)

    def shared_with_emails(self) -> list[str]:
        return ShareGrantService.grantee_emails(KIND_LIVE_TRACK, self.track.id)

    def payload(
        self,
        mode: TrackPayloadMode,
        role: AccessRole,
        *,
        include_secret: bool = False,
        window_key: str | None = None,
        geometry_status: dict | None = None,
        coords_override: list | None = None,
        params_override: list | None = None,
    ) -> dict:
        emails = self.shared_with_emails() if role == AccessRole.OWNER else None
        store = None if mode in (TrackPayloadMode.LIST, TrackPayloadMode.DETAIL) else self.points
        return build_track_payload(
            self.track,
            mode,
            role,
            store=store,
            include_secret=include_secret,
            window_key=window_key,
            shared_with_emails=emails,
            geometry_status=geometry_status,
            coords_override=coords_override,
            params_override=params_override,
        )
