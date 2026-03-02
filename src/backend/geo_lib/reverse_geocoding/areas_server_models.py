"""
Pydantic models for the areas server query response.

Used by the core backend to parse and access the response with typed attributes.
Adding new sections only requires extending these models; accessors stay the same.
"""
from typing import List, Optional, Union

from pydantic import BaseModel, ConfigDict, Field, field_validator


class AdminHierarchy(BaseModel):
    """Country, state, county, city at the point (from admin boundaries + optional place node)."""
    country: Optional[str] = None
    state: Optional[str] = None
    county: Optional[str] = None
    city: Optional[str] = None


class ProtectedArea(BaseModel):
    """One protected area (park, reserve, etc.) containing or near the point."""
    name: str = ""
    protection_title: str = ""
    protect_class: str = ""
    designation: str = ""
    operator: str = ""
    leisure: str = ""
    landuse: str = ""
    boundary: str = ""


class NearbyLake(BaseModel):
    """One nearby water body (lake, reservoir, etc.)."""
    name: str
    water_type: str = "water"
    distance_miles: float = 0.0
    on_water: bool = False


class Waterway(BaseModel):
    """Nearest major waterway (river, creek) from waterways.major_waterways."""
    name: Optional[str] = None
    distance_m: Optional[float] = None


class AreasQueryResponse(BaseModel):
    """Full response from GET /query on the areas server."""
    model_config = ConfigDict(extra="ignore")

    admin_hierarchy: AdminHierarchy = Field(default_factory=AdminHierarchy)
    protected_areas: List[ProtectedArea] = Field(default_factory=list)
    lakes: List[NearbyLake] = Field(default_factory=list)
    ocean: List[str] = Field(default_factory=list)
    ski_resort: Optional[str] = None
    waterway: Optional[Waterway] = None

    @field_validator("ocean", mode="before")
    @classmethod
    def ocean_to_list(cls, v: object) -> List[str]:
        if v is None:
            return []
        if isinstance(v, list):
            return [str(x).strip() for x in v if x and str(x).strip()]
        if isinstance(v, str) and v.strip():
            return [v.strip()]
        return []

    @classmethod
    def empty(cls) -> "AreasQueryResponse":
        """Response with no data (e.g. when areas server errors or is disabled)."""
        return cls(
            admin_hierarchy=AdminHierarchy(),
            protected_areas=[],
            lakes=[],
            ocean=[],
            ski_resort=None,
            waterway=None,
        )

    def has_any_location_data(self) -> bool:
        """True if there is any admin, protected area, or lake data."""
        ah = self.admin_hierarchy
        return bool(
            (ah.country or ah.state or ah.county or ah.city)
            or self.protected_areas
            or self.lakes
        )
