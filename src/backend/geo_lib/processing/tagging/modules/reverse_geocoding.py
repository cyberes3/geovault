"""
Reverse reverse_geocoding tag generator.
Generates location-based tags (city, state, country, protected areas, lakes, etc.)
using reverse geocoding.
"""
from typing import List, Tuple, Dict, Any

from website.settings_utils import get_required_setting, get_setting

from geo_lib.types.feature import GeoFeatureSupported
from geo_lib.reverse_geocoding.constants import REVERSE_GEOCODING_TAG_PREFIXES
from geo_lib.reverse_geocoding.location_tags import batch_reverse_geocode_coordinates
from geo_lib.processing.tagging.base import TagGenerator
from geo_lib.processing.logging import DatabaseLogLevel
from geo_lib.logging.console import get_tagged_logger
from geo_lib.spatial.haversine import haversine_distance_meters

logger = get_tagged_logger()

# Bounds for linestring_geocode_points (clamped when reading setting)
LINESTRING_GEOCODE_POINTS_MIN = 1
LINESTRING_GEOCODE_POINTS_MAX = 100


def _sample_points_along_line(
    coords: List[Any],
    n: int,
) -> List[Tuple[float, float]]:
    """
    Sample N points equally spaced by arc-length along a polyline.

    coords: list of GeoJSON-style [lon, lat] or [lon, lat, ele]; only first two used.
    n: number of points to return (1 = midpoint by distance).

    Returns:
        List of (latitude, longitude) tuples.
    """
    if not coords:
        return []
    # Normalize to (lon, lat) and handle elevation
    pts = []
    for c in coords:
        if len(c) >= 2:
            pts.append((float(c[0]), float(c[1])))
    if not pts:
        return []
    if len(pts) == 1:
        return [(pts[0][1], pts[0][0])]  # (lat, lon)

    n = max(1, n)
    # Cumulative distances (meters); cumul[i] = distance from pts[0] to pts[i]
    cumul = [0.0]
    for i in range(1, len(pts)):
        lon0, lat0 = pts[i - 1][0], pts[i - 1][1]
        lon1, lat1 = pts[i][0], pts[i][1]
        seg = haversine_distance_meters(lat0, lon0, lat1, lon1)
        cumul.append(cumul[-1] + seg)
    total = cumul[-1]

    if total <= 0:
        # Zero-length line (all same point)
        return [(pts[0][1], pts[0][0])]

    result = []
    for i in range(n):
        if n == 1:
            t = total / 2.0
        else:
            t = (i * total / (n - 1)) if n > 1 else 0.0
        t = max(0.0, min(total, t))
        # Find segment: cumul[j] <= t < cumul[j+1], or j = len-2 when t == total
        j = 0
        while j < len(cumul) - 1 and cumul[j + 1] < t:
            j += 1
        if j >= len(cumul) - 1:
            j = len(cumul) - 2
        # Interpolate between pts[j] and pts[j+1]
        a = cumul[j]
        b = cumul[j + 1]
        if b <= a:
            frac = 0.0
        else:
            frac = (t - a) / (b - a)
        frac = max(0.0, min(1.0, frac))
        lon = pts[j][0] + frac * (pts[j + 1][0] - pts[j][0])
        lat = pts[j][1] + frac * (pts[j + 1][1] - pts[j][1])
        result.append((lat, lon))
    return result


def get_representative_points(feature: GeoFeatureSupported) -> List[Tuple[float, float]]:
    """
    Get representative points from a feature for reverse geocoding.
    For points: returns the point itself.
    For linestrings and multilinestrings: returns N points equally spaced by
    distance along the track (N from config linestring_geocode_points, default 4).
    For polygons: returns empty list (not reverse geocoded).

    Returns:
        List of (latitude, longitude) tuples
    """
    points = []
    geometry = feature.geometry
    geom_type = geometry.type.value.lower()

    if geom_type == 'point':
        coords = geometry.coordinates
        points.append((coords[1], coords[0]))  # (lat, lon)

    elif geom_type == 'linestring':
        coords_list = geometry.coordinates
        if coords_list:
            n = get_setting('REVERSE_GEOCODING_LINESTRING_GEOCODE_POINTS', 4)
            if n is None:
                n = 4
            n = max(LINESTRING_GEOCODE_POINTS_MIN, min(LINESTRING_GEOCODE_POINTS_MAX, int(n)))
            points = _sample_points_along_line(coords_list, n)

    elif geom_type == 'multilinestring':
        all_coords = []
        for linestring in geometry.coordinates:
            if linestring:
                all_coords.extend(linestring)
        if all_coords:
            n = get_setting('REVERSE_GEOCODING_LINESTRING_GEOCODE_POINTS', 4)
            if n is None:
                n = 4
            n = max(LINESTRING_GEOCODE_POINTS_MIN, min(LINESTRING_GEOCODE_POINTS_MAX, int(n)))
            points = _sample_points_along_line(all_coords, n)

    return points


class ReverseGeocodingTagGenerator(TagGenerator):
    """Generates location-based tags using reverse geocoding."""
    
    priority = 100  # Execute last (reverse geocoding can be slow)
    
    def __init__(self):
        # Register all reverse geocoding tag prefixes that this generator produces
        # Use the centralized constant to ensure consistency
        super().__init__(REVERSE_GEOCODING_TAG_PREFIXES)
    
    def process_batch(
        self,
        features: List[GeoFeatureSupported],
        import_log,
        **kwargs
    ) -> Dict[int, List[str]]:
        """
        Process multiple features at once with coordinate deduplication.
        This is the preferred method for processing multiple features efficiently.
        
        Args:
            features: List of features to reverse geocode
            import_log: ImportLog for database logging
            
        Returns:
            Dict mapping feature index to list of tags
        """
        if not get_required_setting('REVERSE_GEOCODING_ENABLED'):
            return {i: [] for i in range(len(features))}
        
        # Step 1: Extract all coordinates from all features
        feature_coords = {}  # Maps feature index -> list of coordinates
        all_coordinates = []
        
        for i, feature in enumerate(features):
            geometry_type = feature.geometry.type.value.lower()
            if geometry_type in ['point', 'multipoint', 'linestring', 'multilinestring']:
                points = get_representative_points(feature)
                if points:
                    feature_coords[i] = points
                    all_coordinates.extend(points)
        
        if not all_coordinates:
            return {i: [] for i in range(len(features))}
        
        # Step 2: SINGLE CALL to batch reverse geocode all coordinates with deduplication
        reverse_geocode_results = batch_reverse_geocode_coordinates(all_coordinates)
        
        # Step 3: Assign tags back to features
        feature_tags = {}
        for i, coords in feature_coords.items():
            all_location_tags = set()
            
            for lat, lon in coords:
                tags, log_messages = reverse_geocode_results.get((lat, lon), ([], []))
                all_location_tags.update(tags)
                
                # Add log messages to import log
                if log_messages:
                    for log_msg in log_messages:
                        # Map level string to DatabaseLogLevel
                        if log_msg.level == 'ERROR':
                            level = DatabaseLogLevel.ERROR
                        elif log_msg.level == 'WARNING':
                            level = DatabaseLogLevel.WARNING
                        else:
                            level = DatabaseLogLevel.INFO
                        
                        import_log.add(
                            log_msg.message,
                            log_msg.source,
                            level
                        )
            
            # Deduplicate final tag list (multiple points along track can resolve to same place)
            feature_tags[i] = sorted(all_location_tags)
        
        # Return empty list for features that weren't reverse geocoded
        return {i: feature_tags.get(i, []) for i in range(len(features))}
    
    def process(
        self,
        feature: GeoFeatureSupported,
        import_log=None,
        **kwargs
    ) -> List[str]:
        """
        Process single feature.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog for database logging
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List of reverse geocoding tags
        """
        # Call batch version with single feature
        result = self.process_batch([feature], import_log, **kwargs)
        return result.get(0, [])

