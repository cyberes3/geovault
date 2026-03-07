from django.urls import path

from . import views

urlpatterns = [
    path("trackers/", views.tracker_list_create),
    path("tracker-check/", views.tracker_check),
    path("trackers/<str:tracker_id>/clear-history/", views.tracker_clear_history),
    path("trackers/<str:tracker_id>/settings/", views.tracker_post_settings),
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
]
