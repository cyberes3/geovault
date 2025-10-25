from django.urls import path

from data.views.geojson_api import get_geojson_data, get_feature, update_feature, update_feature_metadata
from data.views.geolocation_api import get_user_location, get_location_by_ip
from data.views.import_item import upload_item, get_import_item_logs, get_processing_status, get_user_processing_jobs, fetch_import_queue, delete_import_item, bulk_delete_import_items, update_import_item, fetch_import_history_item, \
    import_to_featurestore

urlpatterns = [
    path('item/import/upload', upload_item),
    path('item/import/status/<str:job_id>', get_processing_status),
    path('item/import/logs/<int:item_id>', get_import_item_logs),
    path('item/import/jobs', get_user_processing_jobs),
    path('item/import/get/<int:item_id>', fetch_import_queue),
    path('item/import/get/history/<int:item_id>', fetch_import_history_item),
    path('item/import/delete/<int:id>', delete_import_item),
    path('item/import/bulk-delete', bulk_delete_import_items),
    path('item/import/update/<int:item_id>', update_import_item),
    path('item/import/perform/<int:item_id>', import_to_featurestore),
    # GeoJSON API endpoints
    path('geojson/', get_geojson_data),
    path('feature/<int:feature_id>/', get_feature),
    path('feature/<int:feature_id>/update/', update_feature),
    path('feature/<int:feature_id>/update-metadata/', update_feature_metadata),
    # Geolocation API endpoints
    path('location/user/', get_user_location),
    path('location/ip/', get_location_by_ip),
]
