from django.urls import path

from api.views.bbox_query import get_geojson_data
from api.views.config import get_config
from api.views.feature_delete import delete_feature
from api.views.feature_retrieval import get_feature
from api.views.feature_search import get_features_by_tag, search_features
from api.views.feature_update import update_feature, update_feature_metadata, apply_replacement_geometry, regenerate_feature_tags
from api.views.geolocation_api import get_user_location, get_location_by_ip
from api.views.icon_management import serve_user_icon, serve_system_icon, upload_icon, recolor_icon, serve_icon_registry
from api.views.import_item import upload_item, get_processing_status, get_user_processing_jobs, delete_import_item, update_import_item, fetch_import_history_item, \
    import_to_featurestore, get_import_queue_item_features
from api.views.sharing import create_share, list_shares, delete_share, get_public_share

urlpatterns = [
    path('item/import/upload', upload_item),
    path('item/import/status/<str:job_id>', get_processing_status),
    path('item/import/jobs', get_user_processing_jobs),
    path('item/import/get/history/<int:item_id>', fetch_import_history_item),
    path('item/import/get/features/<int:item_id>', get_import_queue_item_features),
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
    path('feature/<int:feature_id>/apply-replacement/', apply_replacement_geometry),
    path('feature/<int:feature_id>/regenerate-tags/', regenerate_feature_tags),
    path('feature/<int:feature_id>/delete/', delete_feature),
    # Config endpoint
    path('config/', get_config),
    # Icon endpoints
    path('icons/upload/', upload_icon),
    path('icons/recolor/', recolor_icon, name='recolor_icon'),
    path('icons/registry/', serve_icon_registry, name='serve_icon_registry'),
    # System icons (built-in icons from assets/icons/)
    path('icons/system/<path:path>', serve_system_icon, name='serve_system_icon'),
    # User icons (uploaded icons with hash)
    path('icons/user/<str:icon_hash>', serve_user_icon, name='serve_user_icon'),
    # Geolocation API endpoints
    path('location/user/', get_user_location),
    path('location/ip/', get_location_by_ip),
    # Sharing API endpoints
    path('sharing/create/', create_share),
    path('sharing/list/', list_shares),
    path('sharing/<str:share_id>/', delete_share),
    path('sharing/public/<str:share_id>/', get_public_share),
]
