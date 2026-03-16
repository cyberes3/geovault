from django.http import HttpResponseRedirect
from django.urls import path, reverse

from . import views
from . import hauk_views
from . import world_share_views


def _redirect_public_share_to_world(request, share_id, is_info: bool):
    """Redirect legacy public/share/ URL to world/share/ for backward compatibility."""
    # Build URL via reverse() from validated share_id only to avoid open redirect (py/url-redirection).
    url_name = "world_share_info" if is_info else "world_share_data"
    new_path = reverse(url_name, kwargs={"share_id": share_id})
    return HttpResponseRedirect(request.build_absolute_uri(new_path))


urlpatterns = [
    path("api/create.php", hauk_views.hauk_create),
    path("api/post.php", hauk_views.hauk_post),
    path("api/stop.php", hauk_views.hauk_stop),
    path("api/adopt.php", hauk_views.hauk_adopt_stub),
    path("api/new-link.php", hauk_views.hauk_new_link_stub),
    path("api/fetch.php", hauk_views.hauk_fetch_stub),
    path("world/share/<str:share_id>/info/", world_share_views.world_share_info, name="world_share_info"),
    path("world/share/<str:share_id>/", world_share_views.world_share_data, name="world_share_data"),
    path("public/share/<str:share_id>/info/", lambda r, share_id: _redirect_public_share_to_world(r, share_id, True)),
    path("public/share/<str:share_id>/", lambda r, share_id: _redirect_public_share_to_world(r, share_id, False)),
    path("trackers/", views.tracker_list_create),
    path("trackers/available-to-add/", views.tracker_available_to_add),
    path("tracker-check/", views.tracker_check),
    path("trackers/<str:tracker_id>/clear-history/", views.tracker_clear_history),
    path("trackers/<str:tracker_id>/regenerate-tokens/", views.tracker_regenerate_tokens),
    path("trackers/<str:tracker_id>/regenerate-hauk-password/", views.tracker_regenerate_hauk_password),
    path("trackers/<str:tracker_id>/subscribe/", views.tracker_subscribe_delete),
    path("trackers/<str:tracker_id>/share-with-me/", views.tracker_leave_share),
    path("trackers/<str:tracker_id>/settings/", views.tracker_post_settings),
    path("trackers/<str:tracker_id>/subscribers/", views.tracker_subscribers),
    path("trackers/geometry/", views.tracker_get_geometry_bulk),
    path("trackers/<str:tracker_id>/", views.tracker_get_patch_delete),
    path("trackers/<str:tracker_id>/geometry/", views.tracker_get_geometry),
    path("trackers/<str:tracker_id>/coordinates/", views.tracker_get_latest_coordinates),
    path("trackers/<str:tracker_id>/kml/", views.tracker_kml),
    path("trackers/<str:tracker_id>/profile.properties", views.tracker_profile_properties),
    path("trackers/<str:tracker_id>/<str:profile_basename>.properties", views.tracker_profile_properties),
    path("ingress-body-template/", views.ingress_body_template),
    path("hauk-config/", views.hauk_config),
    path("ingress/", views.ingress),
    path("ingress", views.ingress),  # no slash: GPSLogger may send here; redirect would drop POST body
    path("app-ingress/", views.app_ingress),
    path("groups/", views.group_list_create),
    path("groups/<str:group_id>/", views.group_get_patch_delete),
    path("groups/<str:group_id>/tracks/", views.group_add_track),
    path("groups/<str:group_id>/tracks/<str:track_id>/", views.group_remove_track),
    path("groups/<str:group_id>/accept-share/", views.group_accept_share),
    path("groups/<str:group_id>/leave/", views.group_leave),
    path("map-visibility/", views.map_visibility_get_patch),
]
