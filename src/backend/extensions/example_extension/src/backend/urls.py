from django.urls import path
from . import views

# ==============================================================================
# Extension URL Routing
# ==============================================================================
# Paths defined here are automatically prefixed by the platform with:
# /api/extensions/example-extension/

urlpatterns = [
    path('items/', views.item_list_create),
    path('items/<int:item_id>/', views.item_delete),
    path('features/', views.feature_list),
    path('features/create/', views.create_feature),
    path('features/<int:feature_id>/modify/', views.modify_feature),
    path('features/<int:feature_id>/delete/', views.delete_feature),
]
