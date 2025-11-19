"""
WSGI config for website project.

It exposes the WSGI callable as a module-level variable named ``application``.

For more information on this file, see
https://docs.djangoproject.com/en/5.0/howto/deployment/wsgi/
"""

import os

from django.core.wsgi import get_wsgi_application

os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'website.settings')

# Initialize Django first (this loads all apps)
application = get_wsgi_application()

# Run startup checks after Django is initialized
from website.startup_checks import run_startup_checks
run_startup_checks()
