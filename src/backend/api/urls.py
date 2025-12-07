from django.urls import path

from api.views.bbox_query import get_geojson_data
from api.views.config import get_config
from api.views.health import health_check
from api.views.feature_delete import delete_feature
from api.views.feature_creation import create_quick_point
from api.views.feature_retrieval import get_feature, get_feature_elevations_external, get_feature_elevations_internal
from api.views.feature_search import (
    get_features_by_tag,
    get_user_tags,
    search_features,
    filter_features_by_tags,
    get_all_features,
)
from api.views.feature_update import update_feature, update_feature_metadata, bulk_update_features_metadata, apply_replacement_geometry, regenerate_feature_tags, apply_bulk_operations_to_tag
from api.views.geolocation_api import get_user_location, get_location_by_ip
from api.views.icon_management import serve_user_icon, serve_system_icon, upload_icon, recolor_icon, serve_icon_registry
from api.views.font_management import serve_font_glyph
from api.views.import_item import upload_item, get_processing_status, get_user_processing_jobs, delete_import_item, update_import_item, fetch_import_history_item, \
    import_to_featurestore, get_import_queue_item_features, search_import_item_features, save_bulk_operations, get_bulk_operations, recheck_duplicates, save_skip_state
from api.views.sharing import create_share, list_shares, delete_share, get_public_share_info, get_public_share, create_collection_share, get_public_collection_share
from api.views.collections import list_collections, create_collection, get_collection, update_collection, delete_collection, get_collection_features, apply_bulk_operations_to_collection
from api.views.user_settings import (
    get_user_settings,
    update_user_setting,
    clear_hidden_features,
    bulk_update_hidden_features,
)
from api.views.tiles import tile_proxy, get_tile_sources
from api.views.feature_export import export_feature_kmz
from api.views.geocoding import geocoding_search

urlpatterns = [
    path('item/import/upload', upload_item),
    path('item/import/status/<str:job_id>', get_processing_status),
    path('item/import/jobs', get_user_processing_jobs),
    path('item/import/get/history/<int:item_id>', fetch_import_history_item),
    path('item/import/get/features/<int:item_id>', get_import_queue_item_features),
    path('item/import/search/<int:item_id>', search_import_item_features),
    path('item/import/delete/<int:id>', delete_import_item),
    path('item/import/update/<int:item_id>', update_import_item),
    path('item/import/perform/<int:item_id>', import_to_featurestore),
    path('item/import/bulk-operations/<int:item_id>', save_bulk_operations),
    path('item/import/bulk-operations/<int:item_id>/get', get_bulk_operations),
    path('item/import/skip-state/<int:item_id>', save_skip_state),
    path('item/import/recheck-duplicates/<int:item_id>', recheck_duplicates),
    # GeoJSON API endpoints
    path('geojson/', get_geojson_data),
    path('features/by-tag/', get_features_by_tag),
    path('features/user-tags/', get_user_tags),
    path('features/search/', search_features),
    path('features/filter-by-tags/', filter_features_by_tags),
    path('features/all/', get_all_features),
    path('features/bulk-update-metadata/', bulk_update_features_metadata),
    path('features/bulk-operations/by-tag/<str:tag_name>/', apply_bulk_operations_to_tag),
    path('features/quick-point/create/', create_quick_point),
    path('feature/<int:feature_id>/', get_feature),
    path('feature/<int:feature_id>/elevations/external/', get_feature_elevations_external),
    path('feature/<int:feature_id>/elevations/internal/', get_feature_elevations_internal),
    path('feature/<int:feature_id>/update/', update_feature),
    path('feature/<int:feature_id>/update-metadata/', update_feature_metadata),
    path('feature/<int:feature_id>/apply-replacement/', apply_replacement_geometry),
    path('feature/<int:feature_id>/regenerate-tags/', regenerate_feature_tags),
    path('feature/<int:feature_id>/delete/', delete_feature),
    path('export-kmz', export_feature_kmz, name='export_feature_kmz'),
    # Config endpoint
    path('config/', get_config),
    # Health check endpoint
    path('health/', health_check),
    # Icon endpoints
    path('icons/upload/', upload_icon),
    path('icons/recolor/', recolor_icon, name='recolor_icon'),
    path('icons/registry/', serve_icon_registry, name='serve_icon_registry'),
    # System icons (built-in icons from assets/icons/)
    path('icons/system/<path:path>', serve_system_icon, name='serve_system_icon'),
    # User icons (uploaded icons with hash)
    path('icons/user/<str:icon_hash>', serve_user_icon, name='serve_user_icon'),
    # Font glyph endpoints (for MapLibre GL JS)
    # Note: fontstack uses <str> not <path> to avoid capturing the range_str
    # Django automatically URL-decodes parameters, so "Noto%20Sans%20Regular" becomes "Noto Sans Regular"
    path('fonts/<str:fontstack>/<str:range_str>', serve_font_glyph, name='serve_font_glyph'),
    # Geolocation API endpoints
    path('location/user/', get_user_location),
    path('location/ip/', get_location_by_ip),
    # Sharing API endpoints
    path('sharing/create/', create_share),
    path('sharing/list/', list_shares),
    path('sharing/<str:share_id>/', delete_share),
    path('sharing/public/info/<str:share_id>/', get_public_share_info),
    path('sharing/public/<str:share_id>/', get_public_share),
    # Collection sharing API endpoints
    path('sharing/collections/create/', create_collection_share),
    path('sharing/public/collection/<str:share_id>/', get_public_collection_share),
    # Collections API endpoints
    path('collections/', list_collections),
    path('collections/create/', create_collection),
    path('collections/<uuid:collection_id>/', get_collection),
    path('collections/<uuid:collection_id>/update/', update_collection),
    path('collections/<uuid:collection_id>/delete/', delete_collection),
    path('collections/<uuid:collection_id>/features/', get_collection_features),
    path('collections/<uuid:collection_id>/bulk-operations/', apply_bulk_operations_to_collection),
    # User settings API endpoints
    path('user/settings/', get_user_settings),
    path('user/settings/update/', update_user_setting),
    path('user/settings/hidden-features/bulk/', bulk_update_hidden_features),
    path('user/settings/hidden-features/clear/', clear_hidden_features),
    # Tile API endpoints
    path('tiles/sources/', get_tile_sources, name='get_tile_sources'),
    path('tiles/<str:service>/<int:z>/<int:x>/<int:y>', tile_proxy, name='tile_proxy'),
    # Geocoding API endpoints
    path('geocoding/search/', geocoding_search, name='geocoding_search'),
]
