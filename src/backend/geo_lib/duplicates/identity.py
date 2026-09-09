from dataclasses import dataclass
from datetime import datetime
from typing import Any

from geo_lib.feature_id import generate_geojson_hash


@dataclass(frozen=True)
class GeoJsonHash:
    value: str

    @classmethod
    def of(cls, feature: dict[str, Any]) -> "GeoJsonHash":
        stored = None
        properties = feature.get('properties')
        if isinstance(properties, dict):
            stored = properties.get('geojson_hash')
        if stored:
            return cls(value=str(stored))
        return cls(value=generate_geojson_hash(feature))

    def __str__(self) -> str:
        return self.value


@dataclass(frozen=True)
class FileIdentity:
    queue_item_id: int
    filename: str
    uploaded_at: datetime
    id: int

    def older_than(self, other: "FileIdentity") -> bool:
        return (self.uploaded_at, self.id) < (other.uploaded_at, other.id)


@dataclass(frozen=True)
class FeatureRef:
    feature_store_id: int | None = None
    draft_queue_id: int | None = None
    spatial_index: int | None = None
    name: str = ''
    geometry_type: str = ''
