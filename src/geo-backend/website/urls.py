"""
URL configuration for website project.

The `urlpatterns` list routes URLs to views. For more information please see:
    https://docs.djangoproject.com/en/5.0/topics/http/urls/
Examples:
Function views
    1. Add an import:  from my_app import views
    2. Add a URL to urlpatterns:  path('', views.home, name='home')
Class-based views
    1. Add an import:  from other_app.views import Home
    2. Add a URL to urlpatterns:  path('', Home.as_view(), name='home')
Including another URLconf
    1. Import the include() function: from django.urls import include, path
    2. Add a URL to urlpatterns:  path('blog/', include('blog.urls'))
"""
from django.conf.urls import include
from django.contrib import admin
from django.urls import path, re_path

from website.views import index, standalone_map, tile_proxy, get_tile_sources, serve_assets
from website.exception_handler import custom_exception_handler

# Set custom exception handler
handler500 = custom_exception_handler

urlpatterns = [
    path('', index),
    path('standalone_map/', standalone_map, name='standalone_map'),
    re_path(r"^account/", include("django.contrib.auth.urls")),
    path('admin/', admin.site.urls),
    path('', include("users.urls")),
    path('api/data/', include("api.urls")),
    path('api/tiles/sources/', get_tile_sources, name='get_tile_sources'),
    path('api/tiles/<str:service>/<int:z>/<int:x>/<int:y>', tile_proxy, name='tile_proxy'),
    # re_path(r'^assets/(?P<path>.*)$', serve_assets, name='serve_assets'),
]
