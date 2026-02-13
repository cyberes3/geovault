from django.urls import path

from . import views

urlpatterns = [
    path('features/', views.places_list, name='places_list'),
    path('features/<int:feature_id>/navigate/', views.place_navigate, name='place_navigate'),
    path('features/<int:feature_id>/', views.place_detail, name='place_detail'),
]
