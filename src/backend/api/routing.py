"""
WebSocket routing for the api app.
"""
from django.urls import re_path

from api.ws_consumers.process_status_consumer import ProcessStatusConsumer
from api.ws_consumers.realtime_consumer import RealtimeConsumer
from website.extensions.capabilities import websocket_routes


def get_websocket_urlpatterns():
    """Build websocket URL patterns. Call at ASGI build after ExtensionRuntime.initialize()."""
    patterns = [
        re_path(r'ws/realtime/$', RealtimeConsumer.as_asgi()),
        re_path(r'ws/upload/status/(?P<item_id>\d+)/$', ProcessStatusConsumer.as_asgi()),
    ]
    for path_regex, consumer_class in websocket_routes():
        patterns.append(re_path(path_regex, consumer_class.as_asgi()))
    return patterns
