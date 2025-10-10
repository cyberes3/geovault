from django.urls import path

from data.views.import_item import upload_item, get_import_item_status, get_processing_status, get_user_processing_jobs, cancel_processing_job, fetch_import_queue, fetch_import_waiting, delete_import_item, bulk_delete_import_items, update_import_item, fetch_import_history, fetch_import_history_item, import_to_featurestore
from data.views.geojson_api import get_geojson_data, get_feature, update_feature
from data.views.geolocation_api import get_user_location, get_location_by_ip

urlpatterns = [
    path('item/import/upload', upload_item),
    path('item/import/status/<str:job_id>', get_processing_status),
    path('item/import/item-status/<int:item_id>', get_import_item_status),
    path('item/import/jobs', get_user_processing_jobs),
    path('item/import/cancel/<str:job_id>', cancel_processing_job),
    path('item/import/get/<int:item_id>', fetch_import_queue),
    path('item/import/get', fetch_import_waiting),
    path('item/import/get/history', fetch_import_history),
    path('item/import/get/history/<int:item_id>', fetch_import_history_item),
    path('item/import/delete/<int:id>', delete_import_item),
    path('item/import/bulk-delete', bulk_delete_import_items),
    path('item/import/update/<int:item_id>', update_import_item),
    path('item/import/perform/<int:item_id>', import_to_featurestore),
    # GeoJSON API endpoints
    path('geojson/', get_geojson_data),
    path('feature/<int:feature_id>/', get_feature),
    path('feature/<int:feature_id>/update/', update_feature),
    # Geolocation API endpoints
    path('location/user/', get_user_location),
    path('location/ip/', get_location_by_ip),
]
