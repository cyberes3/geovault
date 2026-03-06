"""
Live track extension views. Re-exports from tracker_views and ingress_views so urls.py can use a single import.
"""

from .ingress_views import app_ingress, ingress
from .tracker_views import (
    ingress_body_template,
    tracker_check,
    tracker_get_geometry,
    tracker_get_latest_coordinates,
    tracker_get_patch_delete,
    tracker_kml,
    tracker_list_create,
    tracker_profile_properties,
)
