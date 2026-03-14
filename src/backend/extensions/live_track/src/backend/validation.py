"""
Pydantic models for live_track request validation.
Ingress body: only GPSLogger-supported params we accept (no profile, filename, act, timeoffset, spd, aid).
"""

from typing import Annotated, Literal, Optional, Union

from pydantic import BaseModel, ConfigDict, Field
from pydantic.functional_validators import BeforeValidator


def _coerce_float(v):
    """Coerce str/int to float for form-urlencoded clients (e.g. GPSLogger). Invalid -> None for optional use."""
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str):
        s = v.strip()
        if s == "":
            return None
        try:
            return float(s)
        except ValueError:
            return None
    return None


def _coerce_float_required(v):
    """Coerce str/int to float; leave as-is so Pydantic raises for required fields if invalid."""
    if v is None:
        return v
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str):
        s = v.strip()
        if s == "":
            return v
        try:
            return float(s)
        except ValueError:
            return v
    return v


def _coerce_int(v):
    """Coerce str/float to int for form-urlencoded clients. Invalid -> None for optional use."""
    if v is None:
        return None
    if isinstance(v, int):
        return v
    if isinstance(v, (float, str)):
        try:
            return int(float(v))
        except (ValueError, TypeError):
            return None
    return None


def _coerce_ischarging(v):
    """Coerce 'true'/'false' strings to bool; leave other values for Pydantic."""
    if v is None:
        return None
    if isinstance(v, bool):
        return v
    if isinstance(v, str):
        low = v.strip().lower()
        if low in ("true", "1", "yes"):
            return True
        if low in ("false", "0", "no", ""):
            return False
    return v


class TrackerCheckRequest(BaseModel):
    """Request body for POST tracker-check: validate tracker ID and optionally password."""

    tracker_id: str = Field(..., description="UUID of the tracker")
    password: Optional[str] = Field(default=None, description="Optional tracker_secret to verify")


class TrackerCheckResponse(BaseModel):
    """Response for tracker-check: valid and optional tracker name when valid."""

    valid: bool = Field(..., description="Whether the tracker exists and (if password given) secret matches")
    name: Optional[str] = Field(default=None, description="Tracker name when valid")


class TrackSettingsRequest(BaseModel):
    """Request body for POST trackers/<id>/settings/. Name, visibility, share_params, shared_with_emails; color and recent_data_window in settings JSON."""

    model_config = ConfigDict(extra="ignore")

    name: Optional[str] = Field(default=None, description="Tracker name (stored in name column)")
    color: Optional[str] = Field(default=None, description="Display color (stored in settings)")
    recent_data_window: Optional[Literal["1min", "1h", "1d", "1w", "1m"]] = Field(
        default=None, description="Show only points within this window (stored in settings); null = show all"
    )
    visibility: Optional[Literal["private", "shared", "public"]] = Field(
        default=None, description="Who can see and subscribe to this track (public = all authenticated users)"
    )
    share_params_with_recipients: Optional[bool] = Field(
        default=None, description="Whether subscribers can view extended parameters (ser never shared)"
    )
    share_params_with_world: Optional[bool] = Field(
        default=None, description="Whether world (unauthenticated) share link viewers can see extended parameters"
    )
    shared_with_emails: Optional[list[str]] = Field(
        default=None, description="When visibility=shared, list of user emails to share with (replaces existing)"
    )
    world_share_enabled: Optional[bool] = Field(
        default=None, description="When True, create or keep world (unauthenticated) share link; when False, remove it"
    )
    hidden_in_list: Optional[bool] = Field(
        default=None, description="When True, hide this tracker from the sidebar list (owner only)"
    )
    allow_group_reshare: Optional[bool] = Field(
        default=None,
        description="When True, users who have access to this tracker may add it to their groups; default False",
    )


class MapVisibilityPrefsRequest(BaseModel):
    """Request body for PATCH map-visibility/. Optional keys; only provided keys are updated."""

    model_config = ConfigDict(extra="ignore")

    hidden_track_ids: Optional[list[str]] = Field(
        default=None, description="List of track UUIDs to hide on map"
    )
    hidden_group_ids: Optional[list[str]] = Field(
        default=None, description="List of group UUIDs to hide on map"
    )


# Optional params we accept (subset of GPSLogger; exclude profile, filename, act, timeoffset, spd, aid)


class LiveTrackIngressBody(BaseModel):
    """Pydantic model for ingress POST body. Unknown keys are dropped before validation.
    Form-urlencoded clients (e.g. GPSLogger) send all values as strings; we coerce and treat
    unparseable optional numerics as None (e.g. placeholder/corrupt bearing like 'ARING')."""

    model_config = ConfigDict(extra="ignore")

    lat: Annotated[float, BeforeValidator(_coerce_float_required)] = Field(..., description="Latitude")
    lon: Annotated[float, BeforeValidator(_coerce_float_required)] = Field(..., description="Longitude")
    # Optional (stored in point_params); timestamp (epoch sec or ms) used for point time if present
    sat: Annotated[Optional[int], BeforeValidator(_coerce_int)] = None
    desc: Optional[str] = None
    alt: Annotated[Optional[float], BeforeValidator(_coerce_float)] = None
    acc: Annotated[Optional[float], BeforeValidator(_coerce_float)] = None
    bearing: Annotated[Optional[float], BeforeValidator(_coerce_float)] = Field(default=None, description="Bearing in degrees (0–360)")
    prov: Optional[str] = None
    spd_kph: Annotated[Optional[float], BeforeValidator(_coerce_float)] = None
    timestamp: Annotated[Optional[int], BeforeValidator(_coerce_int)] = None
    starttimestamp: Annotated[Optional[int], BeforeValidator(_coerce_int)] = None
    batt: Annotated[Optional[float], BeforeValidator(_coerce_float)] = None
    ischarging: Annotated[Optional[Union[bool, str]], BeforeValidator(_coerce_ischarging)] = None
    ser: Optional[str] = None
    dist: Annotated[Optional[float], BeforeValidator(_coerce_float)] = None


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
