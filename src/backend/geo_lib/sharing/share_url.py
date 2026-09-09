from geo_lib.sharing.constants import AUDIENCE_WORLD, DOMAIN_MAP
from geo_lib.sharing.share_id import ShareId

MAP_SOCIAL_PREFIX = "/share/map/"
TRACK_SOCIAL_PREFIX = "/share/track/"
MAP_SPA_PATH = "/mapshare"
TRACK_SPA_PATH = "/extensions/live-track/share"

PUBLIC_SHARE_PREFIXES = (
    MAP_SPA_PATH,
    MAP_SOCIAL_PREFIX,
    TRACK_SOCIAL_PREFIX,
    TRACK_SPA_PATH,
)


def relative_path(path: str) -> str:
    if not path.startswith("/"):
        return f"/{path}"
    return path


class ShareUrl:
    """Relative share surfaces. Absolute copy is the caller's origin."""

    @staticmethod
    def map_social(share_id: str) -> str:
        return relative_path(f"{MAP_SOCIAL_PREFIX}{share_id}/")

    @staticmethod
    def map_spa(share_id: str) -> str:
        return f"/#/mapshare?id={share_id}"

    @staticmethod
    def track_social(share_id: str) -> str:
        return relative_path(f"{TRACK_SOCIAL_PREFIX}{share_id}/")

    @staticmethod
    def track_spa(share_id: str) -> str:
        return f"/#/extensions/live-track/share?id={share_id}"

    @staticmethod
    def for_link(domain: str, share_id: str, audience: str | None = None) -> str:
        if domain == DOMAIN_MAP:
            return ShareUrl.map_social(share_id)
        if audience == AUDIENCE_WORLD:
            return ShareUrl.track_social(share_id)
        return ShareUrl.track_spa(share_id)

    @staticmethod
    def remap_pathname_to_hash(pathname: str, hash_value: str = "") -> str | None:
        if hash_value:
            return None
        map_id = _uuid4_from_prefixed_path(pathname, MAP_SOCIAL_PREFIX)
        if map_id is not None:
            return ShareUrl.map_spa(map_id)
        track_id = _uuid4_from_prefixed_path(pathname, TRACK_SOCIAL_PREFIX)
        if track_id is not None:
            return ShareUrl.track_spa(track_id)
        return None

    @staticmethod
    def public_share_prefixes() -> tuple[str, ...]:
        return PUBLIC_SHARE_PREFIXES

    @staticmethod
    def parse_map_social_path(pathname: str) -> str | None:
        return _uuid4_from_prefixed_path(pathname, MAP_SOCIAL_PREFIX)

    @staticmethod
    def parse_track_social_path(pathname: str) -> str | None:
        return _uuid4_from_prefixed_path(pathname, TRACK_SOCIAL_PREFIX)


def _uuid4_from_prefixed_path(pathname: str, prefix: str) -> str | None:
    if not pathname.startswith(prefix):
        return None
    rest = pathname[len(prefix):].strip("/")
    parsed = ShareId.parse(rest)
    if parsed is None:
        return None
    return parsed.value
