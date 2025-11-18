from django.urls import path

from api.views.bbox_query import get_geojson_data
from api.views.config import get_config
from api.views.feature_delete import delete_feature
from api.views.feature_retrieval import get_feature
from api.views.feature_search import get_features_by_tag, search_features
from api.views.feature_update import update_feature, update_feature_metadata
from api.views.geolocation_api import get_user_location, get_location_by_ip
from api.views.icon_management import serve_icon, serve_asset_icon, upload_icon, recolor_icon
from api.views.import_item import upload_item, get_processing_status, get_user_processing_jobs, delete_import_item, update_import_item, fetch_import_history_item, \
    import_to_featurestore

urlpatterns = [
    path('item/import/upload', upload_item),
    path('item/import/status/<str:job_id>', get_processing_status),
    path('item/import/jobs', get_user_processing_jobs),
    path('item/import/get/history/<int:item_id>', fetch_import_history_item),
    path('item/import/delete/<int:id>', delete_import_item),
    path('item/import/update/<int:item_id>', update_import_item),
    path('item/import/perform/<int:item_id>', import_to_featurestore),
    # GeoJSON API endpoints
    path('geojson/', get_geojson_data),
    path('features/by-tag/', get_features_by_tag),
    path('features/search/', search_features),
    path('feature/<int:feature_id>/', get_feature),
    path('feature/<int:feature_id>/update/', update_feature),
    path('feature/<int:feature_id>/update-metadata/', update_feature_metadata),
    path('feature/<int:feature_id>/delete/', delete_feature),
    # Config endpoint
    path('config/', get_config),
    # Icon endpoints
    path('icons/upload/', upload_icon),
    path('icons/recolor/', recolor_icon, name='recolor_icon'),
    # Path-based icons (with slashes, e.g., caltopo/tidepool.png) - must come before hash route
    path('icons/<path:path>', serve_asset_icon, name='serve_asset_icon'),
    # Hash-based icons (64-char hash + extension, e.g., abc123...def456.png)
    path('icons/<str:icon_hash>', serve_icon, name='serve_icon'),
    # Geolocation API endpoints
    path('location/user/', get_user_location),
    path('location/ip/', get_location_by_ip),
]
