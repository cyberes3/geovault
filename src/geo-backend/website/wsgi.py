"""
WSGI config for website project.

It exposes the WSGI callable as a module-level variable named ``application``.

For more information on this file, see
https://docs.djangoproject.com/en/5.0/howto/deployment/wsgi/
"""

import os

from django.core.wsgi import get_wsgi_application

os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'website.settings')

# Run startup checks before creating the WSGI application
from website.startup_checks import run_startup_checks
run_startup_checks()

application = get_wsgi_application()
