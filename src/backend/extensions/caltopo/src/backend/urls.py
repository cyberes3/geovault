from django.urls import path

from extensions.caltopo.src.backend.views import connect_caltopo, get_caltopo_status, disconnect_caltopo, list_caltopo_maps, get_caltopo_map_details, get_caltopo_map_features, import_caltopo_feature, import_caltopo_map

urlpatterns = [
    # CalTopo authentication endpoints
    path('connect/', connect_caltopo, name='connect'),
    path('status/', get_caltopo_status, name='status'),
    path('disconnect/', disconnect_caltopo, name='disconnect'),

    # CalTopo maps endpoints
    path('maps/', list_caltopo_maps, name='list_maps'),
    path('maps/<str:map_id>/', get_caltopo_map_details, name='map_details'),
    path('maps/<str:map_id>/features/', get_caltopo_map_features, name='map_features'),

    # CalTopo import endpoints
    path('import/feature/', import_caltopo_feature, name='import_feature'),
    path('import/map/', import_caltopo_map, name='import_map'),
]
