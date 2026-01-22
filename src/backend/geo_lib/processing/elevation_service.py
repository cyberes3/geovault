"""
Elevation service for filling missing elevation data in Point, MultiPoint, LineString and MultiLineString features.
Uses racemap's elevation API to fetch elevation data for coordinates.
"""
import time
import traceback
from typing import Dict, Any, List, Tuple, Optional

import requests

from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from website.settings_utils import get_required_setting

_logger = get_tagged_logger()

# Maximum points per API request (limited to 100 to prevent timeout issues)
MAX_POINTS_PER_REQUEST = 100


def _fetch_elevation_batch_with_retry(
        api_url: str,
        batch_coords: List[List[float]],
        api_timeout: int,
        import_log: ImportLog,
        context_info: str = ""
) -> Optional[List[float]]:
    """
    Fetch elevation data for a batch of coordinates with automatic retry logic.
    
    Implements retry logic with exponential backoff similar to the Overpass API.
    Retries on timeout exceptions up to 3 times with increasing wait times.
    
    Args:
        api_url: Elevation API URL
        batch_coords: List of coordinate pairs in [lat, lon] format
        api_timeout: Request timeout in seconds
        import_log: ImportLog instance for logging
        context_info: Optional context string for logging (e.g., "batch starting at index 0")
    
    Returns:
        List of elevation values (floats) if successful, None on failure
    """
    max_retries = 3
    retry_wait_time = 0

    for attempt in range(max_retries):
        if attempt > 0:
            time.sleep(retry_wait_time)

        try:
            response = requests.post(
                api_url,
                json=batch_coords,
                headers={'Content-Type': 'application/json'},
                timeout=api_timeout
            )
            
            # Check status code - treat non-200 as retryable
            if response.status_code != 200:
                retry_wait_time = 10 * (2 ** attempt)
                retries_left = max_retries - (attempt + 1)
                wait_msg = f"waiting {retry_wait_time}s, " if retries_left > 0 else ""
                retry_msg = f"{retries_left} retries left" if retries_left > 0 else "no retries left"

                context_str = f" for {context_info}" if context_info else ""
                import_log.add(
                    f"Elevation API returned status {response.status_code}{context_str}, "
                    f"attempt {attempt + 1}/{max_retries}, {wait_msg}{retry_msg}",
                    "Elevation Service",
                    DatabaseLogLevel.WARNING
                )

                if retries_left == 0:
                    import_log.add(f"Elevation API request failed with status {response.status_code} after {max_retries} attempts{context_str}", "Elevation Service", DatabaseLogLevel.ERROR)
                    return None
                continue

            batch_elevations = response.json()
            if not isinstance(batch_elevations, list):
                import_log.add(f"Unexpected API response format{f' for {context_info}' if context_info else ''}", "Elevation Service", DatabaseLogLevel.WARNING)
                return None

            if len(batch_elevations) != len(batch_coords):
                import_log.add(
                    f"API returned {len(batch_elevations)} elevations for {len(batch_coords)} points"
                    f"{f' ({context_info})' if context_info else ''}",
                    "Elevation Service",
                    DatabaseLogLevel.WARNING
                )
                return None

            # Convert elevations to float
            elevations = []
            for elevation in batch_elevations:
                if isinstance(elevation, (int, float)):
                    elevations.append(float(elevation))
                else:
                    elevations.append(None)

            return elevations

        except requests.exceptions.Timeout:
            retry_wait_time = 10 * (2 ** attempt)
            retries_left = max_retries - (attempt + 1)
            wait_msg = f"waiting {retry_wait_time}s, " if retries_left > 0 else ""
            retry_msg = f"{retries_left} retries left" if retries_left > 0 else "no retries left"

            context_str = f" for {context_info}" if context_info else ""
            import_log.add(
                f"Elevation API request timeout{context_str}, "
                f"attempt {attempt + 1}/{max_retries}, {wait_msg}{retry_msg}",
                "Elevation Service",
                DatabaseLogLevel.WARNING
            )

            if retries_left == 0:
                import_log.add(f"Elevation API request timed out after {api_timeout}s{context_str}: {traceback.format_exc()}", "Elevation Service", DatabaseLogLevel.ERROR)
                return None
            continue

        except requests.exceptions.RequestException as e:
            context_str = f" for {context_info}" if context_info else ""
            import_log.add(f"Elevation API request failed{context_str}: {str(e)}", "Elevation Service", DatabaseLogLevel.ERROR)
            return None

        except:
            context_str = f" for {context_info}" if context_info else ""
            import_log.add(f"Unexpected error fetching elevation data{context_str}: {traceback.format_exc()}", "Elevation Service", DatabaseLogLevel.ERROR)
            return None

    return None


def fill_missing_elevations(geojson_data: Dict[str, Any], import_log: ImportLog) -> Dict[str, Any]:
    """
    Fill missing elevation data for Point, MultiPoint, LineString and MultiLineString features.
    
    Identifies points with missing elevation (coordinates with only 2 elements: [lon, lat] or third element is 0.0)
    and fetches elevation data from racemap's elevation API. Updates coordinates to include
    elevation: [lon, lat, elevation].
    
    Args:
        geojson_data: GeoJSON data dictionary with features
        import_log: ImportLog instance for logging
        
    Returns:
        Updated GeoJSON data dictionary with elevation data filled in
    """
    # Check if elevation API is enabled
    if not get_required_setting('ELEVATION_API_ENABLED'):
        import_log.add("Elevation API is disabled - elevation data will not be filled for points, lines and tracks", "Elevation Service", DatabaseLogLevel.INFO)
        return geojson_data

    elevation_start = time.time()
    api_url = get_required_setting('ELEVATION_API_URL')
    api_timeout = get_required_setting('ELEVATION_API_TIMEOUT')

    features = geojson_data.get('features', [])
    if not features:
        return geojson_data

    # Collect all points that need elevation data
    points_to_fetch: List[Tuple[int, int, int]] = []  # (feature_idx, line_idx, point_idx)
    # line_idx: -2 for Point, -3 for MultiPoint, -1 for LineString, >= 0 for MultiLineString line index
    # point_idx: 0 for Point, point index for MultiPoint/MultiLineString, coordinate index for LineString

    total_points_checked = 0
    total_points_missing = 0

    # First pass: identify all points missing elevation
    for feature_idx, feature in enumerate(features):
        geometry = feature.get('geometry', {})
        geom_type = geometry.get('type', '').lower()

        # Process Point, MultiPoint, LineString and MultiLineString
        if geom_type not in ['point', 'multipoint', 'linestring', 'multilinestring']:
            continue

        coordinates = geometry.get('coordinates', [])
        if not coordinates:
            continue

        if geom_type == 'point':
            # Point: coordinates is [lon, lat] or [lon, lat, elevation]
            total_points_checked += 1
            # Missing elevation if: no third coordinate OR third coordinate is 0.0 (common placeholder)
            if len(coordinates) == 2 or (len(coordinates) >= 3 and coordinates[2] == 0.0):
                # Store as (feature_idx, -2, 0) where -2 indicates Point
                points_to_fetch.append((feature_idx, -2, 0))
                total_points_missing += 1

        elif geom_type == 'multipoint':
            # MultiPoint: coordinates is [[lon, lat], ...] or [[lon, lat, elevation], ...]
            for point_idx, coord in enumerate(coordinates):
                total_points_checked += 1
                # Missing elevation if: no third coordinate OR third coordinate is 0.0 (common placeholder)
                if len(coord) == 2 or (len(coord) >= 3 and coord[2] == 0.0):
                    # Store as (feature_idx, -3, point_idx) where -3 indicates MultiPoint
                    points_to_fetch.append((feature_idx, -3, point_idx))
                    total_points_missing += 1

        elif geom_type == 'linestring':
            # LineString: coordinates is [[lon, lat], [lon, lat], ...] or [[lon, lat, ele], ...]
            for point_idx, coord in enumerate(coordinates):
                total_points_checked += 1
                # Missing elevation if: no third coordinate OR third coordinate is 0.0 (common placeholder)
                if len(coord) == 2 or (len(coord) >= 3 and coord[2] == 0.0):
                    # Store as (feature_idx, -1, point_idx) where -1 indicates LineString
                    points_to_fetch.append((feature_idx, -1, point_idx))
                    total_points_missing += 1

        elif geom_type == 'multilinestring':
            # MultiLineString: coordinates is [[[lon, lat], ...], [[lon, lat], ...], ...]
            for line_idx, line in enumerate(coordinates):
                if not isinstance(line, list):
                    continue
                for point_idx, coord in enumerate(line):
                    total_points_checked += 1
                    # Missing elevation if: no third coordinate OR third coordinate is 0.0 (common placeholder)
                    if len(coord) == 2 or (len(coord) >= 3 and coord[2] == 0.0):
                        points_to_fetch.append((feature_idx, line_idx, point_idx))
                        total_points_missing += 1

    if total_points_missing == 0:
        if total_points_checked > 0:
            import_log.add(f"All {total_points_checked} points already have elevation data", "Elevation Service", DatabaseLogLevel.INFO)
        return geojson_data

    # Prepare coordinate pairs for API (convert GeoJSON [lon, lat] to API [lat, lon])
    api_coords: List[List[float]] = []
    point_mapping: List[Tuple[int, int, int]] = []  # Maps API response index to (feature_idx, line_idx, point_idx)
    # For Point: line_idx = -2, point_idx = 0
    # For MultiPoint: line_idx = -3, point_idx = point index
    # For LineString: line_idx = -1, point_idx = coordinate index
    # For MultiLineString: line_idx = line index, point_idx = point index within line

    encountered_preparing_point_error = 0

    try:
        for feature_idx, line_idx, point_idx in points_to_fetch:
            try:
                feature = features[feature_idx]
                geometry = feature.get('geometry', {})
                coordinates = geometry.get('coordinates', [])

                if line_idx == -2:  # Point
                    # For Point, coordinates is [lon, lat] or [lon, lat, elevation]
                    if not isinstance(coordinates, (list, tuple)) or len(coordinates) < 2:
                        _logger.warning(f"Skipping invalid Point coordinate at feature {feature_idx}: expected list/tuple with 2+ elements, got {type(coordinates).__name__}")
                        continue
                    # Convert [lon, lat] to [lat, lon] for API
                    try:
                        api_coords.append([float(coordinates[1]), float(coordinates[0])])
                        point_mapping.append((feature_idx, -2, 0))  # -2 indicates Point
                    except (IndexError, ValueError, TypeError) as e:
                        _logger.warning(f"Skipping Point coordinate at feature {feature_idx}: cannot convert to lat/lon - {str(e)}")
                        continue

                elif line_idx == -3:  # MultiPoint
                    # For MultiPoint, coordinates is [[lon, lat], ...] or [[lon, lat, elevation], ...]
                    if point_idx >= len(coordinates):
                        _logger.warning(f"Skipping invalid point index {point_idx} for MultiPoint feature {feature_idx} (has {len(coordinates)} points)")
                        continue
                    coord = coordinates[point_idx]
                    # Validate coordinate is a list with at least 2 elements
                    if not isinstance(coord, (list, tuple)) or len(coord) < 2:
                        _logger.warning(f"Skipping invalid coordinate at feature {feature_idx}, point {point_idx}: expected list/tuple with 2+ elements, got {type(coord).__name__}")
                        continue
                    # Convert [lon, lat] to [lat, lon] for API
                    try:
                        api_coords.append([float(coord[1]), float(coord[0])])
                        point_mapping.append((feature_idx, -3, point_idx))  # -3 indicates MultiPoint
                    except (IndexError, ValueError, TypeError) as e:
                        _logger.warning(f"Skipping coordinate at feature {feature_idx}, point {point_idx}: cannot convert to lat/lon - {str(e)}")
                        continue

                elif line_idx == -1:  # LineString
                    # For LineString, coordinates is a list of points: [[lon, lat], ...]
                    if point_idx >= len(coordinates):
                        _logger.warning(f"Skipping invalid point index {point_idx} for LineString feature {feature_idx} (has {len(coordinates)} points)")
                        continue
                    coord = coordinates[point_idx]
                    # Validate coordinate is a list with at least 2 elements
                    if not isinstance(coord, (list, tuple)) or len(coord) < 2:
                        _logger.warning(f"Skipping invalid coordinate at feature {feature_idx}, point {point_idx}: expected list/tuple with 2+ elements, got {type(coord).__name__}")
                        continue
                    # Convert [lon, lat] to [lat, lon] for API
                    try:
                        api_coords.append([float(coord[1]), float(coord[0])])
                        point_mapping.append((feature_idx, -1, point_idx))  # -1 indicates LineString
                    except (IndexError, ValueError, TypeError) as e:
                        _logger.warning(f"Skipping coordinate at feature {feature_idx}, point {point_idx}: cannot convert to lat/lon - {str(e)}")
                        continue
                else:  # MultiLineString: line_idx is the line index
                    # For MultiLineString, coordinates is a list of lines: [[[lon, lat], ...], ...]
                    if line_idx >= len(coordinates):
                        _logger.warning(f"Skipping invalid line index {line_idx} for MultiLineString feature {feature_idx} (has {len(coordinates)} lines)")
                        continue
                    line = coordinates[line_idx]
                    if not isinstance(line, (list, tuple)):
                        _logger.warning(f"Skipping invalid line at feature {feature_idx}, line {line_idx}: expected list/tuple, got {type(line).__name__}")
                        continue
                    if point_idx >= len(line):
                        _logger.warning(f"Skipping invalid point index {point_idx} for line {line_idx} in feature {feature_idx} (line has {len(line)} points)")
                        continue
                    coord = line[point_idx]
                    # Validate coordinate is a list with at least 2 elements
                    if not isinstance(coord, (list, tuple)) or len(coord) < 2:
                        _logger.warning(f"Skipping invalid coordinate at feature {feature_idx}, line {line_idx}, point {point_idx}: expected list/tuple with 2+ elements, got {type(coord).__name__}")
                        continue
                    # Convert [lon, lat] to [lat, lon] for API
                    try:
                        api_coords.append([float(coord[1]), float(coord[0])])
                        point_mapping.append((feature_idx, line_idx, point_idx))
                    except (IndexError, ValueError, TypeError) as e:
                        _logger.warning(f"Skipping coordinate at feature {feature_idx}, line {line_idx}, point {point_idx}: cannot convert to lat/lon - {str(e)}")
                        continue
            except (IndexError, KeyError, TypeError) as e:
                encountered_preparing_point_error += 1
                _logger.error(f"Error preparing coordinate for feature {feature_idx}, line {line_idx}, point {point_idx}: {str(e)}")
                continue
    except:
        import_log.add('Error preparing coordinates for API', "Elevation Service", DatabaseLogLevel.ERROR)
        _logger.error(f"Error preparing coordinates for API: {traceback.format_exc()}")
        return geojson_data

    if encountered_preparing_point_error:
        import_log.add(f'Error preparing coordinate for {encountered_preparing_point_error} feature(s)', "Elevation Service", DatabaseLogLevel.ERROR)

    # Calculate number of batches needed
    num_batches = (len(api_coords) + MAX_POINTS_PER_REQUEST - 1) // MAX_POINTS_PER_REQUEST
    import_log.add(f"Filling elevation data for {total_points_missing} points ({num_batches} batch{'es' if num_batches > 1 else ''})", "Elevation Service", DatabaseLogLevel.INFO)

    # Fetch elevation data in batches
    elevations: List[Optional[float]] = [None] * len(api_coords)
    total_fetched = 0

    # Process in batches of MAX_POINTS_PER_REQUEST
    for batch_start in range(0, len(api_coords), MAX_POINTS_PER_REQUEST):
        batch_end = min(batch_start + MAX_POINTS_PER_REQUEST, len(api_coords))
        batch_coords = api_coords[batch_start:batch_end]
        batch_num = batch_start // MAX_POINTS_PER_REQUEST + 1

        # Only log batch progress if there are multiple batches
        if num_batches > 1:
            import_log.add(f"Fetching elevation data: batch {batch_num}/{num_batches} ({len(batch_coords)} points)", "Elevation Service", DatabaseLogLevel.INFO)

        batch_elevations = _fetch_elevation_batch_with_retry(
            api_url,
            batch_coords,
            api_timeout,
            import_log,
            f"batch starting at index {batch_start}"
        )

        if batch_elevations is None:
            import_log.add(f'Elevation API request failed, skipping batch', "Elevation Service", DatabaseLogLevel.WARNING)
            continue

        # Store elevations for this batch
        for i, elevation in enumerate(batch_elevations):
            if elevation is not None:
                elevations[batch_start + i] = elevation
                total_fetched += 1

    if total_fetched == 0:
        import_log.add("No elevation data was successfully fetched from API", "Elevation Service", DatabaseLogLevel.WARNING)
        return geojson_data

    # Update coordinates with elevation data
    updated_count = 0
    try:
        for api_idx, (feature_idx, line_idx, point_idx) in enumerate(point_mapping):
            elevation = elevations[api_idx]
            if elevation is None:
                continue

            feature = features[feature_idx]
            geometry = feature.get('geometry', {})
            coordinates = geometry.get('coordinates', [])

            if line_idx == -2:  # Point
                # Update coordinate from [lon, lat] to [lon, lat, elevation]
                if len(coordinates) >= 3:
                    coordinates[2] = elevation
                else:
                    coordinates.append(elevation)
                updated_count += 1
            elif line_idx == -3:  # MultiPoint
                # Update coordinate from [lon, lat] to [lon, lat, elevation]
                coord = coordinates[point_idx]
                if len(coord) >= 3:
                    coord[2] = elevation
                else:
                    coord.append(elevation)
                updated_count += 1
            elif line_idx == -1:  # LineString
                # Update coordinate from [lon, lat] to [lon, lat, elevation]
                coordinates[point_idx] = [coordinates[point_idx][0], coordinates[point_idx][1], elevation]
                updated_count += 1
            else:  # MultiLineString: line_idx is the line index
                line = coordinates[line_idx]
                # Update coordinate from [lon, lat] to [lon, lat, elevation]
                line[point_idx] = [line[point_idx][0], line[point_idx][1], elevation]
                updated_count += 1
    except:
        import_log.add('Error updating coordinates with elevation data', "Elevation Service", DatabaseLogLevel.ERROR)
        _logger.error(f"Error updating coordinates with elevation data: {traceback.format_exc()}")

    elevation_duration = time.time() - elevation_start
    if updated_count < total_points_missing:
        import_log.add(f"Filled elevation data for {updated_count} of {total_points_missing} points in {elevation_duration:.1f}s", "Elevation Service", DatabaseLogLevel.INFO)
    else:
        import_log.add(f"Filled elevation data for {updated_count} points in {elevation_duration:.1f}s", "Elevation Service", DatabaseLogLevel.INFO)

    return geojson_data
