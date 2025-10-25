"""
WebSocket routing for the data app.
"""

from django.urls import re_path
from . import consumers

websocket_urlpatterns = [
    re_path(r'ws/import-queue/$', consumers.ImportQueueConsumer.as_asgi()),
]
