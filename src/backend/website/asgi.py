"""
ASGI config for website project.

It exposes the ASGI callable as a module-level variable named ``application``.

For more information on this file, see
https://docs.djangoproject.com/en/5.0/howto/deployment/asgi/
"""

import os
from channels.routing import ProtocolTypeRouter, URLRouter
from channels.auth import AuthMiddlewareStack
from django.core.asgi import get_asgi_application

os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'website.settings')

# Initialize Django first (this loads all apps)
django_asgi_app = get_asgi_application()

# Set up global exception handlers to ensure all unhandled exceptions are logged
from website.exception_handler import setup_global_exception_handlers
setup_global_exception_handlers()

# Run startup checks after Django is initialized
from website.startup_checks import run_startup_checks
run_startup_checks()

# Import routing after Django is set up
from api.routing import websocket_urlpatterns

# Wrap the ASGI application with exception handling middleware
from website.exception_handler import ASGIExceptionMiddleware
from website.websocket_token_auth import WebSocketTokenAuthMiddleware
from website.websocket_origin_validation import SessionOriginValidator

# Create the base application. WebSocket: token auth (for native apps) first, then Origin
# validation (skipped for token-authenticated connections, enforced for the session-cookie path
# below), then session auth.
base_application = ProtocolTypeRouter({
    "http": django_asgi_app,
    "websocket": WebSocketTokenAuthMiddleware(
        SessionOriginValidator(
            AuthMiddlewareStack(
                URLRouter(
                    websocket_urlpatterns
                )
            )
        )
    ),
})

# Wrap with exception middleware to catch all unhandled exceptions
application = ASGIExceptionMiddleware(base_application)
