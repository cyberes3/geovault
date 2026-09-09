"""Typed cache-key builders. Tests fail if a filter field is omitted from the string."""
import hashlib


class GeocodeKey:
    """Forward-geocode identity: provider mode + query hash."""

    def __init__(self, mode: str, query: str):
        if not mode:
            raise ValueError("mode is required")
        self.mode = mode
        normalized = query.strip().lower()
        self.query_hash = hashlib.md5(normalized.encode("utf-8")).hexdigest()

    def __str__(self) -> str:
        return f"reverse_geocoding:{self.mode}:{self.query_hash}"


class TileKey:
    """Raster tile identity: source + z/x/y."""

    def __init__(self, source_id: str, z: int, x: int, y: int):
        if not source_id:
            raise ValueError("source_id is required")
        self.source_id = source_id
        self.z = int(z)
        self.x = int(x)
        self.y = int(y)

    def __str__(self) -> str:
        return f"tile:{self.source_id}:{self.z}:{self.x}:{self.y}"
