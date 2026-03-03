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
from django.http import HttpResponse


from users.views.account_management import block_account_email_view
from website.exception_handler import custom_exception_handler
from website.views import index

def well_known_routing(request, path):
    """
    Dynamic routing for /.well-known/ items registered by extensions.
    """
    from website.extensions.extension_hooks import get_well_known_callback
    callback = get_well_known_callback(path)
    if callback:
        return callback(request)
    return HttpResponse(status=404)

# Set custom exception handler
handler500 = custom_exception_handler

import os
from pathlib import Path

from django.views.static import serve
from django.conf import settings

from geo_lib.utils.secure_path import is_path_under_base, secure_path
from website.settings import EXTENSIONS_DIR

def serve_extension_static(request, path, **kwargs):
    """
    Custom static server that maps hyphenated URL segments to underscored directories.
    Example: extensions/static/example-extension/ -> extensions/example_extension/
    """
    import logging
    logger = logging.getLogger('django')

    ext_folder = None
    parts = path.split('/', 1)
    if parts:
        ext_folder = parts[0].replace('-', '_')
        if len(parts) > 1:
            path = os.path.join(ext_folder, parts[1])
        else:
            path = ext_folder

    path = secure_path(path)

    extensions_base = Path(EXTENSIONS_DIR)
    try:
        candidate = (extensions_base / path).resolve()
    except (OSError, RuntimeError):
        return HttpResponse(status=404)
    if not is_path_under_base(candidate, extensions_base):
        return HttpResponse(status=404)
    if ext_folder is not None:
        extension_base = extensions_base / ext_folder
        if not is_path_under_base(candidate, extension_base):
            return HttpResponse(status=404)

    logger.debug(f"Extension static serving: {path} (root: {EXTENSIONS_DIR})")
    kwargs['document_root'] = EXTENSIONS_DIR
    response = serve(request, path, **kwargs)
    if response.status_code == 200:
        if settings.DEBUG:
            # In dev, avoid long cache so rebuilt extension assets are picked up without restart
            response['Cache-Control'] = 'no-cache, must-revalidate'
        else:
            response['Cache-Control'] = 'public, max-age=31536000, immutable'
    return response

urlpatterns = [
    path('', index, name='index'),  # Root route
    re_path(r'^\.well-known/(?P<path>.*)$', well_known_routing),
    # Block access to /accounts/email/ and redirect to frontend settings
    path('accounts/email/', block_account_email_view, name='account_email'),
    path('accounts/', include('allauth.urls')),  # Django allauth URLs
    path('api/oauth/', include('website.oauth_urls')),
    path('admin/', admin.site.urls),
    path('', include("users.urls")),
    path('api/', include("api.urls")),
    re_path(r'^extensions/static/(?P<path>.*)$', serve_extension_static),
    # Catch-all route for Vue.js router (must be last)
    # Serves index.html for any route that doesn't match above patterns
    # Vue router uses hash-based routing, so this handles direct navigation to non-API routes
    re_path(r'^(?!api/|admin/|accounts/|static/|extensions/static/).+$', index),
]
