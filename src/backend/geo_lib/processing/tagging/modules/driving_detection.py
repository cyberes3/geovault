"""
Driving detection tag generator.
Detects tracks with moving average speeds between 15-120 mph and generates driving:yes tag.
"""
from datetime import datetime
from typing import List, Tuple

from geo_lib.processing.tagging.base import TagGenerator
from geo_lib.spatial.haversine import haversine_distance_meters
from geo_lib.types.feature import GeoFeatureSupported

# Speed thresholds in m/s
# 15 mph = 6.7056 m/s
# 120 mph = 53.6448 m/s
MIN_DRIVING_SPEED_MPS = 6.7056  # 15 mph
MAX_DRIVING_SPEED_MPS = 53.6448  # 120 mph

# Moving average window size (same as frontend)
MOVING_AVERAGE_WINDOW_SIZE = 10


def calculate_moving_average_speed(
        coordinates: List[List[float]],
        timestamps: List[str]
) -> float:
    """
    Calculate moving average speed from coordinates and timestamps.
    
    Uses a rolling window approach similar to the frontend implementation.
    Returns the overall average of all moving averages.
    
    Args:
        coordinates: List of [lon, lat] or [lon, lat, elevation] coordinates
        timestamps: List of ISO timestamp strings
        
    Returns:
        Moving average speed in m/s, or 0.0 if insufficient data
    """
    if len(coordinates) < 2 or len(timestamps) < 2:
        return 0.0

    # Calculate speeds for each segment
    speeds = []

    for i in range(1, len(coordinates)):
        if i >= len(timestamps):
            break

        # Extract coordinates
        prev_coord = coordinates[i - 1]
        curr_coord = coordinates[i]

        if len(prev_coord) < 2 or len(curr_coord) < 2:
            continue

        lon1, lat1 = prev_coord[0], prev_coord[1]
        lon2, lat2 = curr_coord[0], curr_coord[1]

        # Calculate distance in meters
        distance_meters = haversine_distance_meters(lat1, lon1, lat2, lon2)

        # Parse timestamps
        try:
            ts1_str = str(timestamps[i - 1])
            ts2_str = str(timestamps[i])
            # Handle 'Z' timezone indicator (UTC)
            if ts1_str.endswith('Z'):
                ts1_str = ts1_str[:-1] + '+00:00'
            if ts2_str.endswith('Z'):
                ts2_str = ts2_str[:-1] + '+00:00'
            time1 = datetime.fromisoformat(ts1_str)
            time2 = datetime.fromisoformat(ts2_str)
        except (ValueError, AttributeError, TypeError):
            continue

        # Calculate time difference in seconds
        time_diff_seconds = (time2 - time1).total_seconds()

        # Filter out invalid segments (zero or negative time, zero distance)
        if time_diff_seconds > 0 and distance_meters > 0:
            speed_mps = distance_meters / time_diff_seconds
            speeds.append(speed_mps)

    if not speeds:
        return 0.0

    # Calculate moving averages using rolling window
    window_size = min(MOVING_AVERAGE_WINDOW_SIZE, len(speeds))
    moving_averages = []

    for i in range(len(speeds)):
        start = max(0, i - window_size // 2)
        end = min(len(speeds), i + (window_size + 1) // 2)
        window = speeds[start:end]
        avg = sum(window) / len(window)
        moving_averages.append(avg)

    # Return average of all moving averages
    if not moving_averages:
        return 0.0

    overall_moving_avg = sum(moving_averages) / len(moving_averages)
    return overall_moving_avg


def extract_coordinates_and_timestamps(feature: GeoFeatureSupported) -> Tuple[List[List[float]], List[str]]:
    """
    Extract coordinates and timestamps from a feature.
    
    Handles both LineString and MultiLineString geometries.
    
    Args:
        feature: The feature to extract data from
        
    Returns:
        Tuple of (coordinates_list, timestamps_list) or ([], []) if not available
    """
    geometry = feature.geometry
    geometry_type = geometry.type.value.lower()

    # Extract coordinates
    coordinates = []
    if geometry_type == 'linestring':
        coordinates = geometry.coordinates
    elif geometry_type == 'multilinestring':
        # Flatten MultiLineString coordinates
        for line in geometry.coordinates:
            coordinates.extend(line)
    else:
        return [], []

    # Extract timestamps from coordinateProperties
    props_dict = feature.properties.model_dump()
    coordinate_properties = props_dict.get('coordinateProperties', {})

    if not coordinate_properties or not isinstance(coordinate_properties, dict):
        return [], []

    times = coordinate_properties.get('times')
    if not times or not isinstance(times, list):
        return [], []

    # Handle MultiLineString timestamps (array of arrays)
    timestamps = []
    if geometry_type == 'multilinestring':
        # Flatten MultiLineString timestamps
        for line_times in times:
            if isinstance(line_times, list):
                timestamps.extend(line_times)
            else:
                timestamps.append(line_times)
    else:
        timestamps = times

    return coordinates, timestamps


class DrivingDetectionTagGenerator(TagGenerator):
    """Detects tracks with driving speeds and generates driving:yes tag."""

    priority = 50  # Execute after track detection (40), before geocoding (60)

    def __init__(self):
        super().__init__('driving')

    def process(
            self,
            feature: GeoFeatureSupported,
            import_log=None,
            **kwargs
    ) -> List[str]:
        """
        Detect if feature is a track with driving speeds and generate driving:yes tag.
        
        Checks if moving average speed is between 15-120 mph.
        Only processes tracks with timestamps (GPX tracks/routes) and at least 10 points.
        
        Args:
            feature: The feature to generate tags for
            import_log: Optional ImportLog (not used here)
            **kwargs: Additional keyword arguments (not used)
            
        Returns:
            List containing driving:yes tag if detected, empty list otherwise
        """
        tags = []

        geometry_type = feature.geometry.type.value.lower()

        # Only process LineString and MultiLineString features
        if geometry_type not in ['linestring', 'multilinestring']:
            return tags

        # Extract coordinates and timestamps
        coordinates, timestamps = extract_coordinates_and_timestamps(feature)

        # Need at least 10 points with timestamps to calculate reliable speed
        if len(coordinates) < 10 or len(timestamps) < 10:
            return tags

        # Calculate moving average speed
        moving_avg_speed_mps = calculate_moving_average_speed(coordinates, timestamps)

        # Check if speed is in driving range (15-120 mph)
        if MIN_DRIVING_SPEED_MPS <= moving_avg_speed_mps <= MAX_DRIVING_SPEED_MPS:
            tags.append('driving:yes')

        return tags
