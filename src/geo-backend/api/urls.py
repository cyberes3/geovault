from django.urls import path

from api.views.geojson_api import get_geojson_data, get_feature, update_feature, update_feature_metadata, delete_feature, serve_icon, get_config, get_features_by_tag
from api.views.geolocation_api import get_user_location, get_location_by_ip
from api.views.import_item import upload_item, get_processing_status, get_user_processing_jobs, delete_import_item, bulk_delete_import_items, update_import_item, fetch_import_history_item, \
    import_to_featurestore

urlpatterns = [
    path('item/import/upload', upload_item),
    path('item/import/status/<str:job_id>', get_processing_status),
    path('item/import/jobs', get_user_processing_jobs),
    path('item/import/get/history/<int:item_id>', fetch_import_history_item),
    path('item/import/delete/<int:id>', delete_import_item),
    path('item/import/bulk-delete', bulk_delete_import_items),
    path('item/import/update/<int:item_id>', update_import_item),
    path('item/import/perform/<int:item_id>', import_to_featurestore),
    # GeoJSON API endpoints
    path('geojson/', get_geojson_data),
    path('features/by-tag/', get_features_by_tag),
    path('feature/<int:feature_id>/', get_feature),
    path('feature/<int:feature_id>/update/', update_feature),
    path('feature/<int:feature_id>/update-metadata/', update_feature_metadata),
    path('feature/<int:feature_id>/delete/', delete_feature),
    # Config endpoint
    path('config/', get_config),
    # Icon serving endpoint
    path('icons/<str:icon_hash>', serve_icon, name='serve_icon'),
    # Geolocation API endpoints
    path('location/user/', get_user_location),
    path('location/ip/', get_location_by_ip),
]
