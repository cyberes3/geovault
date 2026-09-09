"""Catalog rows: id, name, geometry type. Full GeoJSON is for map and export only."""

from typing import Any, Mapping

from pydantic import BaseModel


class FeatureListItem(BaseModel):
    id: int
    name: str
    geometry_type: str


class ListProjection:
    def project(self, feature_id: int, properties: Mapping[str, Any] | None, geometry: Mapping[str, Any] | None) -> dict[str, Any]:
        name = ""
        if properties:
            raw_name = properties.get("name")
            if isinstance(raw_name, str):
                name = raw_name
        geometry_type = ""
        if geometry:
            raw_type = geometry.get("type")
            if isinstance(raw_type, str):
                geometry_type = raw_type
        return FeatureListItem(
            id=feature_id,
            name=name,
            geometry_type=geometry_type,
        ).model_dump()
