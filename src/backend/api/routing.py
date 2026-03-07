"""
WebSocket routing for the api app.
"""

from django.urls import re_path

from api.ws_consumers.process_status_consumer import ProcessStatusConsumer
from api.ws_consumers.realtime_consumer import RealtimeConsumer
from website.extensions.extension_hooks import get_registered_websocket_routes

websocket_urlpatterns = [
    re_path(r'ws/realtime/$', RealtimeConsumer.as_asgi()),
    re_path(r'ws/upload/status/(?P<item_id>\d+)/$', ProcessStatusConsumer.as_asgi()),
]
for path_regex, consumer_class in get_registered_websocket_routes():
    websocket_urlpatterns.append(re_path(path_regex, consumer_class.as_asgi()))
