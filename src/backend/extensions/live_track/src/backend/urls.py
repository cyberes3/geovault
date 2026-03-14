from django.http import HttpResponseRedirect
from django.urls import path

from . import views
from . import world_share_views


def _redirect_public_share_to_world(request, share_id):
    """Redirect legacy public/share/ URL to world/share/ for backward compatibility."""
    new_path = request.path.replace("/public/share/", "/world/share/", 1)
    return HttpResponseRedirect(request.build_absolute_uri(new_path))


urlpatterns = [
    path("world/share/<str:share_id>/info/", world_share_views.world_share_info),
    path("world/share/<str:share_id>/", world_share_views.world_share_data),
    path("public/share/<str:share_id>/info/", lambda r, share_id: _redirect_public_share_to_world(r, share_id)),
    path("public/share/<str:share_id>/", lambda r, share_id: _redirect_public_share_to_world(r, share_id)),
    path("trackers/", views.tracker_list_create),
    path("trackers/available-to-add/", views.tracker_available_to_add),
    path("tracker-check/", views.tracker_check),
    path("trackers/<str:tracker_id>/clear-history/", views.tracker_clear_history),
    path("trackers/<str:tracker_id>/subscribe/", views.tracker_subscribe_delete),
    path("trackers/<str:tracker_id>/share-with-me/", views.tracker_leave_share),
    path("trackers/<str:tracker_id>/settings/", views.tracker_post_settings),
    path("trackers/<str:tracker_id>/subscribers/", views.tracker_subscribers),
    path("trackers/<str:tracker_id>/", views.tracker_get_patch_delete),
    path("trackers/<str:tracker_id>/geometry/", views.tracker_get_geometry),
    path("trackers/<str:tracker_id>/coordinates/", views.tracker_get_latest_coordinates),
    path("trackers/<str:tracker_id>/kml/", views.tracker_kml),
    path("trackers/<str:tracker_id>/profile.properties", views.tracker_profile_properties),
    path("trackers/<str:tracker_id>/<str:profile_basename>.properties", views.tracker_profile_properties),
    path("ingress-body-template/", views.ingress_body_template),
    path("ingress/", views.ingress),
    path("ingress", views.ingress),  # no slash: GPSLogger may send here; redirect would drop POST body
    path("app-ingress/", views.app_ingress),
    path("groups/", views.group_list_create),
    path("groups/<str:group_id>/", views.group_get_patch_delete),
    path("groups/<str:group_id>/tracks/", views.group_add_track),
    path("groups/<str:group_id>/tracks/<str:track_id>/", views.group_remove_track),
    path("groups/<str:group_id>/leave/", views.group_leave),
    path("map-visibility/", views.map_visibility_get_patch),
]
