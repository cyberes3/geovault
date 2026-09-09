from dataclasses import dataclass
from typing import Any

from geo_lib.duplicates.constants import COORDINATE_TOLERANCE
from geo_lib.spatial.coordinates import geometries_match, normalize_coordinates


@dataclass(frozen=True)
class GeometryFingerprint:
    geom_type: str
    coordinates: list

    @classmethod
    def of(cls, feature: dict[str, Any]) -> "GeometryFingerprint | None":
        geometry = feature.get('geometry') or {}
        geom_type = str(geometry.get('type') or '').lower()
        coordinates = geometry.get('coordinates')
        if not geom_type or not coordinates:
            return None
        if geom_type == 'geometrycollection':
            return None
        return cls(geom_type=geom_type, coordinates=normalize_coordinates(coordinates))

    def matches(self, other: "GeometryFingerprint") -> bool:
        if self.geom_type != other.geom_type:
            return False
        return geometries_match(self.coordinates, other.coordinates, COORDINATE_TOLERANCE)
