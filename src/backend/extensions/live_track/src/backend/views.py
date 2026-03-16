"""
Live track extension views. Re-exports from tracker_views and ingress_views so urls.py can use a single import.
"""

from .group_views import (
    group_accept_share,
    group_add_track,
    group_get_patch_delete,
    group_leave,
    group_list_create,
    group_remove_track,
)
from .ingress_views import app_ingress, ingress
from .tracker_views import (
    hauk_config,
    ingress_body_template,
    map_visibility_get_patch,
    tracker_available_to_add,
    tracker_check,
    tracker_clear_history,
    tracker_get_geometry,
    tracker_get_geometry_bulk,
    tracker_get_latest_coordinates,
    tracker_get_patch_delete,
    tracker_kml,
    tracker_leave_share,
    tracker_list_create,
    tracker_post_settings,
    tracker_profile_properties,
    tracker_regenerate_tokens,
    tracker_regenerate_hauk_password,
    tracker_subscribe_delete,
    tracker_subscribers,
)
