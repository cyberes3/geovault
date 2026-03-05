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
    batt: Optional[float] = None
    ischarging: Optional[Union[bool, str]] = None
    ser: Optional[str] = None
    hdop: Optional[str] = None
    vdop: Optional[str] = None
    pdop: Optional[str] = None
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
    "dir": "Direction",
    "prov": "Provider",
    "spd_kph": "Speed",
    "starttimestamp": "Start Timestamp",
    "batt": "Battery",
    "ischarging": "Charging",
    "ser": "Serial",
    "hdop": "HDOP",
    "vdop": "VDOP",
    "pdop": "PDOP",
    "dist": "Distance",
}

# GPSLogger-style placeholders for each field (uppercase key or common name)
INGRESS_BODY_PLACEHOLDERS = {
    "lat": "%LAT",
    "lon": "%LON",
    "timestamp": "%TIMESTAMP",
    "sat": "%SAT",
    "desc": "%DESC",
    "alt": "%ALT",
    "acc": "%ACC",
    "dir": "%DIR",
    "prov": "%PROV",
    "spd_kph": "%SPD",
    "starttimestamp": "%STARTTIMESTAMP",
    "batt": "%BATT",
    "ischarging": "%ISCHARGING",
    "ser": "%SER",
    "hdop": "%HDOP",
    "vdop": "%VDOP",
    "pdop": "%PDOP",
    "dist": "%DIST",
}


def get_ingress_body_template() -> str:
    """Build form-urlencoded body template with all supported params (single source of truth from LiveTrackIngressBody)."""
    parts = []
    for name in LiveTrackIngressBody.model_fields:
        placeholder = INGRESS_BODY_PLACEHOLDERS.get(name, f"%{name.upper()}")
        parts.append(f"{name}={placeholder}")
    return "&".join(parts)
