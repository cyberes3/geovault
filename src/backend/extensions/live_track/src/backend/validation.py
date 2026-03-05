"""
Pydantic models for live_track request validation.
Ingress body: only GPSLogger-supported params we accept (no profile, filename, act, timeoffset, spd, aid).
"""

from typing import Optional, Union

from pydantic import BaseModel, ConfigDict, Field


class TrackerCheckRequest(BaseModel):
    """Request body for POST tracker-check: validate tracker ID and optionally password."""

    tracker_id: str = Field(..., description="UUID of the tracker")
    password: Optional[str] = Field(default=None, description="Optional tracker_secret to verify")


class TrackerCheckResponse(BaseModel):
    """Response for tracker-check: valid and optional tracker name when valid."""

    valid: bool = Field(..., description="Whether the tracker exists and (if password given) secret matches")
    name: Optional[str] = Field(default=None, description="Tracker name when valid")

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
    bearing: Optional[float] = Field(default=None, description="Bearing in degrees (0–360)")
    prov: Optional[str] = None
    spd_kph: Optional[float] = None
    timestamp: Optional[int] = None
    starttimestamp: Optional[int] = None
    batt: Optional[float] = None
    ischarging: Optional[Union[bool, str]] = None
    ser: Optional[str] = None
    dist: Optional[float] = None


# Human-readable labels for the params table (e.g. in Latest Params modal)
PARAM_PRETTY_NAMES = {
    "lat": "Latitude",
    "lon": "Longitude",
    "timestamp": "Timestamp",
    "sat": "Satellites",
    "desc": "Description",
    "alt": "Altitude",
    "acc": "Accuracy",
    "bearing": "Bearing",
    "prov": "Provider",
    "spd_kph": "Speed",
    "starttimestamp": "Start Timestamp",
    "batt": "Battery",
    "ischarging": "Charging",
    "ser": "Serial",
    "dist": "Distance",
}

# Placeholders for GPSLogger config: param name -> %PLACEHOLDER (e.g. bearing=%BEARING in log_customurl_body).
INGRESS_BODY_PLACEHOLDERS = {
    "lat": "%LAT",
    "lon": "%LON",
    "timestamp": "%TIMESTAMP",
    "sat": "%SAT",
    "desc": "%DESC",
    "alt": "%ALT",
    "acc": "%ACC",
    "bearing": "%BEARING",
    "prov": "%PROV",
    "spd_kph": "%SPD",
    "starttimestamp": "%STARTTIMESTAMP",
    "batt": "%BATT",
    "ischarging": "%ISCHARGING",
    "ser": "%SER",
    "dist": "%DIST",
}


def get_ingress_body_template() -> str:
    """Build form-urlencoded body template with all supported params (single source of truth from LiveTrackIngressBody)."""
    parts = []
    for name in LiveTrackIngressBody.model_fields:
        placeholder = INGRESS_BODY_PLACEHOLDERS.get(name, f"%{name.upper()}")
        parts.append(f"{name}={placeholder}")
    return "&".join(parts)
