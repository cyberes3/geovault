"""Aggregate geographic extent for the authenticated user's main-map features (scope NULL)."""

from typing import Optional

from django.contrib.gis.db.models.aggregates import Extent
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from pydantic import BaseModel, Field

from api.models import FeatureStore
from geo_lib.website.auth import api_or_login_required_401

_MAX_MERCATOR_LAT = 85.05112878
# Minimum half-width in degrees when extent collapses to a point or near-degenerate line
_POINT_PAD_DEG = 0.02


class DataExtentHintResponse(BaseModel):
    bbox: Optional[list[float]] = Field(
        default=None,
        description="Web Mercator–safe bounds: min_lon, min_lat, max_lon, max_lat",
    )


def _clamp_lat(lat: float) -> float:
    return max(min(lat, _MAX_MERCATOR_LAT), -_MAX_MERCATOR_LAT)


def _normalize_extent(extent) -> Optional[list[float]]:
    """
    Turn PostGIS Extent aggregate into [min_lon, min_lat, max_lon, max_lat] or None.
    Expands degenerate extents so fitBounds is valid.
    """
    if not extent:
        return None
    if len(extent) != 4:
        return None

    min_lon, min_lat, max_lon, max_lat = extent
    if min_lon is None or min_lat is None or max_lon is None or max_lat is None:
        return None

    min_lon = float(min_lon)
    min_lat = _clamp_lat(float(min_lat))
    max_lon = float(max_lon)
    max_lat = _clamp_lat(float(max_lat))

    if min_lon > max_lon:
        min_lon, max_lon = max_lon, min_lon
    if min_lat > max_lat:
        min_lat, max_lat = max_lat, min_lat

    lon_span = max_lon - min_lon
    lat_span = max_lat - min_lat
    if lon_span < 1e-9 and lat_span < 1e-9:
        min_lon -= _POINT_PAD_DEG
        max_lon += _POINT_PAD_DEG
        min_lat = _clamp_lat(min_lat - _POINT_PAD_DEG)
        max_lat = _clamp_lat(max_lat + _POINT_PAD_DEG)
    elif lon_span < 1e-9:
        min_lon -= _POINT_PAD_DEG
        max_lon += _POINT_PAD_DEG
    elif lat_span < 1e-9:
        min_lat = _clamp_lat(min_lat - _POINT_PAD_DEG)
        max_lat = _clamp_lat(max_lat + _POINT_PAD_DEG)

    min_lon = max(-180.0, min(180.0, min_lon))
    max_lon = max(-180.0, min(180.0, max_lon))

    return [min_lon, min_lat, max_lon, max_lat]


@api_or_login_required_401()
@require_http_methods(["GET"])
def get_data_extent_hint(request):
    """
    Return aggregate bbox of all main-map features (geometry present, scope IS NULL).
    Used when the first viewport bbox load returns no features (e.g. no geolocation).
    """
    extent = (
        FeatureStore.objects.filter(user=request.user, scope__isnull=True)
        .exclude(geometry__isnull=True)
        .aggregate(extent=Extent("geometry"))
        .get("extent")
    )
    bbox = _normalize_extent(extent)
    return JsonResponse(DataExtentHintResponse(bbox=bbox).model_dump())
