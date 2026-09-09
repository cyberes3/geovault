"""
Track name override step.

Optionally overwrites a single track's name with the source filename, based on
per-user import settings (`import.overwrite_single_track_name_with_filename`).
"""
import os
import traceback
from typing import Any, Dict, Optional

from api.models import UserSettings
from geo_lib.logging.console import get_tagged_logger
from geo_lib.processing.logging import DatabaseLogLevel, ImportLog

_logger = get_tagged_logger('TRACK_OVERRIDE')


def apply_track_name_override(
    geojson_data: Dict[str, Any],
    filename: str,
    user_id: Optional[int],
    job_id: Optional[str],
) -> ImportLog:
    """
    Apply the user's track-name-override setting, if enabled, to `geojson_data`
    (modified in-place) when it contains exactly one tagged track feature.
    """
    step_log = ImportLog()

    if not user_id:
        return step_log

    try:
        user_settings_obj = UserSettings.objects.filter(user_id=user_id).first()
        if user_settings_obj and user_settings_obj.settings:
            import_settings = user_settings_obj.settings.get('import', {})
            overwrite_enabled = import_settings.get('overwrite_single_track_name_with_filename', False)

            if overwrite_enabled:
                features = geojson_data.get('features', [])
                # Check if there's exactly one feature
                if len(features) == 1:
                    feature = features[0]
                    geometry = feature.get('geometry', {})
                    geometry_type = geometry.get('type', '').lower() if geometry else ''
                    properties = feature.get('properties', {})

                    # Check if it's a track (LineString or MultiLineString)
                    is_track_geometry = geometry_type in ['linestring', 'multilinestring']

                    # Check if it has the type:track tag
                    system_tags = properties.get('system_tags', [])
                    is_track_tagged = 'type:track' in system_tags if isinstance(system_tags, list) else False

                    if is_track_geometry and is_track_tagged:
                        # Extract filename without extension
                        filename_without_ext = os.path.splitext(filename)[0]
                        # Store original name before overwriting
                        original_name = properties.get('name', '')
                        if original_name:
                            properties['original_name'] = original_name
                        # Overwrite the name property
                        properties['name'] = filename_without_ext
                        feature['properties'] = properties
                        _logger.info(f"Overwrote single track name with filename '{filename_without_ext}' for job {job_id}")
                        step_log.add(f"Applied track name override: '{filename_without_ext}'", "Track Name Override", DatabaseLogLevel.INFO)
    except Exception as e:
        _logger.error(f"Error applying track name override: {traceback.format_exc()}")
        step_log.add(f"Failed to apply track name override: {str(e)}", "Track Name Override", DatabaseLogLevel.ERROR)

    return step_log
