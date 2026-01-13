from django.urls import path
from . import views

# ==============================================================================
# Extension URL Routing
# ==============================================================================
# Paths defined here are automatically prefixed by the platform with:
# /api/extensions/example_extension/
#
# For example, 'items/' becomes /api/extensions/example_extension/items/

urlpatterns = [
    # Map raw paths to your view functions
    path('items/', views.item_list_create),
    path('items/<int:item_id>/', views.item_delete),
]
