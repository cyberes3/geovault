from django.urls import path

from api.views.assets.fonts import serve_font_glyph
from api.views.assets.icons import serve_user_icon, serve_system_icon, upload_icon, recolor_icon, serve_icon_registry
from api.views.collections.bulk_operations import apply_bulk_operations_to_collection
from api.views.collections.management import (
    list_collections,
    create_collection,
    get_collection,
    update_collection,
    delete_collection,
    get_collection_features,
)
from api.views.config import get_config
from api.views.features.bbox_query import get_geojson_data
from api.views.features.creation import create_quick_point
from api.views.features.deletion import delete_feature
from api.views.features.export import export_feature_kmz
from api.views.features.retrieval import get_feature, get_feature_elevations_external, get_feature_elevations_internal
from api.views.features.search import (
    get_features_by_tag,
    get_user_tags,
    search_features,
    filter_features_by_tags,
    get_all_features,
)
from api.views.features.updates.bulk_operations import apply_bulk_operations_to_tag
from api.views.features.updates.geometry import update_feature, apply_replacement_geometry
from api.views.features.updates.metadata import update_feature_metadata, bulk_update_features_metadata
from api.views.features.updates.tags import regenerate_feature_tags
from api.views.health import health_check
from api.views.imports.bulk_operations import save_bulk_operations, get_bulk_operations, save_skip_state
from api.views.imports.duplicates import recheck_duplicates
from api.views.imports.queue_management import (
    get_processing_status,
    get_user_processing_jobs,
    get_all_job_statuses,
    fetch_import_history_item,
    get_import_queue_item_features,
    search_import_item_features,
    delete_import_item,
    update_import_item,
    import_to_featurestore,
)
from api.views.imports.upload import upload_item
from api.views.services.geocoding import geocoding_search
from api.views.services.geolocation import get_user_location, get_location_by_ip
from api.views.services.tiles import tile_proxy, get_tile_sources
from api.views.sharing.collections import create_collection_share, get_public_collection_share
from api.views.sharing.management import list_shares, delete_share
from api.views.sharing.tags import create_share, get_public_share_info, get_public_share
from api.views.user.settings import (
    get_user_settings,
    update_user_setting,
    clear_hidden_features,
    bulk_update_hidden_features,
)

urlpatterns = [
    # Import endpoints
    path('item/import/upload', upload_item),
    path('item/import/status/<str:job_id>', get_processing_status),
    path('item/import/jobs', get_user_processing_jobs),
    path('item/import/jobs/all', get_all_job_statuses),
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

    # Feature endpoints
    path('feature/<int:feature_id>/', get_feature),
    path('feature/<int:feature_id>/elevations/', get_feature_elevations_external),  # Default to external
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
    path('icons/recolor/', recolor_icon),
    path('icons/registry/', serve_icon_registry),
    path('icons/system/<path:path>', serve_system_icon),
    path('icons/user/<str:icon_hash>', serve_user_icon),

    # Font glyph endpoints (for MapLibre GL JS)
    path('fonts/<str:fontstack>/<str:range_str>', serve_font_glyph),

    # Geolocation API endpoints
    path('location/user/', get_user_location),
    path('location/ip/', get_location_by_ip),

    # Sharing API endpoints
    path('sharing/create/', create_share),
    path('sharing/list/', list_shares),
    path('sharing/<str:share_id>/', delete_share),
    path('sharing/public/info/<str:share_id>/', get_public_share_info),
    path('sharing/public/<str:share_id>/', get_public_share),
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
    path('tiles/sources/', get_tile_sources),
    path('tiles/<str:service>/<int:z>/<int:x>/<int:y>', tile_proxy),

    # Geocoding API endpoints
    path('geocoding/search/', geocoding_search),
]
