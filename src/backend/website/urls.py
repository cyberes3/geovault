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
from django.contrib.admin import AdminSite
from django.urls import path, re_path
from django.http import HttpResponse


from users.views.account_management import block_account_email_view


class SuperuserOnlyAdminSite(AdminSite):
    def has_permission(self, request):
        return request.user.is_active and request.user.is_superuser


admin.site.__class__ = SuperuserOnlyAdminSite
from website.exception_handler import custom_exception_handler
from website.map_share_social.views import (
    map_share_social_page,
    map_share_social_preview_image,
    track_share_social_page,
)
from website.views import index

def well_known_routing(request, path):
    """
    Dynamic routing for /.well-known/ items registered by extensions.
    """
    from website.extensions.capabilities import get_well_known_callback
    callback = get_well_known_callback(path)
    if callback:
        return callback(request)
    return HttpResponse(status=404)

# Set custom exception handler
handler500 = custom_exception_handler

from website.extensions.static_bundle import ExtensionStaticBundle

_extension_static = ExtensionStaticBundle()


def serve_extension_static(request, path, **kwargs):
    return _extension_static.serve(request, path, **kwargs)

urlpatterns = [
    path('', index, name='index'),  # Root route
    re_path(r'^\.well-known/(?P<path>.*)$', well_known_routing),
    # Block access to /accounts/email/ and redirect to frontend settings
    path('accounts/email/', block_account_email_view, name='account_email'),
    path('accounts/', include('allauth.urls')),  # Django allauth URLs
    path('api/oauth/', include('website.oauth.urls')),
    path('admin/', admin.site.urls),
    path('', include("users.urls")),
    path('api/', include("api.urls")),
    path('share/map/<str:share_id>/', map_share_social_page, name='map_share_social_page'),
    path('share/map/<str:share_id>/preview.png', map_share_social_preview_image, name='map_share_social_preview_image'),
    path('share/track/<str:share_id>/', track_share_social_page, name='track_share_social_page'),
    re_path(r'^extensions/static/(?P<path>.*)$', serve_extension_static),
    # Catch-all route for Vue.js router (must be last)
    # Serves index.html for any route that doesn't match above patterns
    # Vue router uses hash-based routing, so this handles direct navigation to non-API routes
    re_path(r'^(?!api/|admin/|accounts/|static/|extensions/static/).+$', index),
]
