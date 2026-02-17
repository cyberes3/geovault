from django.urls import path
from .views import download_pwa_apk, admin_force_regenerate_pwa_apk

urlpatterns = [
    path('download/', download_pwa_apk, name='pwa_download'),
    path('admin/force-regenerate/', admin_force_regenerate_pwa_apk, name='pwa_admin_force_regenerate'),
]
