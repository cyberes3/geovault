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

from website.views import index
from website.exception_handler import custom_exception_handler

# Set custom exception handler
handler500 = custom_exception_handler

urlpatterns = [
    path('', index, name='index'),  # Root route
    path('accounts/', include('allauth.urls')),  # Django allauth URLs
    path('admin/', admin.site.urls),
    path('', include("users.urls")),
    path('api/', include("api.urls")),
    # Catch-all route for Vue.js router (must be last)
    # Serves index.html for any route that doesn't match above patterns
    # Vue router uses hash-based routing, so this handles direct navigation to non-API routes
    re_path(r'^(?!api/|admin/|accounts/|static/).+$', index),
]
