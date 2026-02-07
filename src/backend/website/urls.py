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

from users.views.account_management import block_account_email_view
from website.exception_handler import custom_exception_handler
from website.views import index

# Set custom exception handler
handler500 = custom_exception_handler

import os

from django.views.static import serve

from geo_lib.utils.secure_path import secure_path
from website.settings import EXTENSIONS_DIR

def serve_extension_static(request, path, **kwargs):
    """
    Custom static server that maps hyphenated URL segments to underscored directories.
    Example: extensions/static/example-extension/ -> extensions/example_extension/
    """
    import logging
    logger = logging.getLogger('django')
    
    parts = path.split('/', 1)
    if parts:
        ext_folder = parts[0].replace('-', '_')
        if len(parts) > 1:
            path = os.path.join(ext_folder, parts[1])
        else:
            path = ext_folder

    path = secure_path(path)
    
    logger.debug(f"Extension static serving: {path} (root: {EXTENSIONS_DIR})")
    kwargs['document_root'] = EXTENSIONS_DIR
    return serve(request, path, **kwargs)

urlpatterns = [
    path('', index, name='index'),  # Root route
    # Block access to /accounts/email/ and redirect to frontend settings
    path('accounts/email/', block_account_email_view, name='account_email'),
    path('accounts/', include('allauth.urls')),  # Django allauth URLs
    path('admin/', admin.site.urls),
    path('', include("users.urls")),
    path('api/', include("api.urls")),
    re_path(r'^extensions/static/(?P<path>.*)$', serve_extension_static),
    # Catch-all route for Vue.js router (must be last)
    # Serves index.html for any route that doesn't match above patterns
    # Vue router uses hash-based routing, so this handles direct navigation to non-API routes
    re_path(r'^(?!api/|admin/|accounts/|static/|extensions/static/).+$', index),
]
