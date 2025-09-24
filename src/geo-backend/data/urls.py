from django.urls import path

from data.views.import_item import upload_item, fetch_import_queue, fetch_import_waiting, delete_import_item, update_import_item, fetch_import_history, fetch_import_history_item, import_to_featurestore
from data.views.geojson_api import get_geojson_data, get_cache_stats, clear_cache

urlpatterns = [
    path('item/import/upload', upload_item),
    path('item/import/get/<int:item_id>', fetch_import_queue),
    path('item/import/get', fetch_import_waiting),
    path('item/import/get/history', fetch_import_history),
    path('item/import/get/history/<int:item_id>', fetch_import_history_item),
    path('item/import/delete/<int:id>', delete_import_item),
    path('item/import/update/<int:item_id>', update_import_item),
    path('item/import/perform/<int:item_id>', import_to_featurestore),
    # GeoJSON API endpoints
    path('geojson/', get_geojson_data),
    path('geojson/cache/stats', get_cache_stats),
    path('geojson/cache/clear', clear_cache),
]
