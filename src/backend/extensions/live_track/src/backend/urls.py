from django.urls import path

from . import views

urlpatterns = [
    path("trackers/", views.tracker_list_create),
    path("trackers/<str:tracker_id>/", views.tracker_get_patch_delete),
    path("trackers/<str:tracker_id>/kml/", views.tracker_kml),
    path("ingress/", views.ingress),
    path("ingress", views.ingress),  # no slash: GPSLogger may send here; redirect would drop POST body
    path("app-ingress/", views.app_ingress),
]
