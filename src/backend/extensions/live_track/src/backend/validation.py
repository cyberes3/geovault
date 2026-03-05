"""
Pydantic models for live_track request validation.
Ingress body: only GPSLogger-supported params we accept (no profile, filename, act, timeoffset, spd, aid).
"""

from typing import Optional, Union

from pydantic import BaseModel, ConfigDict, Field

# Optional params we accept (subset of GPSLogger; exclude profile, filename, act, timeoffset, spd, aid)


class LiveTrackIngressBody(BaseModel):
    """Pydantic model for ingress POST body. Unknown keys are dropped before validation."""

    model_config = ConfigDict(extra="ignore")

    lat: float = Field(..., description="Latitude")
    lon: float = Field(..., description="Longitude")
    # Optional (stored in point_params); timestamp (epoch sec or ms) used for point time if present
    sat: Optional[int] = None
    desc: Optional[str] = None
    alt: Optional[float] = None
    acc: Optional[float] = None
    dir: Optional[float] = None
    prov: Optional[str] = None
    spd_kph: Optional[float] = None
    timestamp: Optional[int] = None
    starttimestamp: Optional[int] = None
    date: Optional[str] = None
    batt: Optional[float] = None
    ischarging: Optional[Union[bool, str]] = None
    ser: Optional[str] = None
    hdop: Optional[str] = None
    vdop: Optional[str] = None
    pdop: Optional[str] = None
    dist: Optional[float] = None
