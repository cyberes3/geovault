"""
WebSocket routing for the api app.
"""

from django.urls import re_path

from api.ws_consumers.realtime_consumer import RealtimeConsumer
from api.ws_consumers.upload_status_consumer import UploadStatusConsumer

websocket_urlpatterns = [
    re_path(r'ws/realtime/$', RealtimeConsumer.as_asgi()),
    re_path(r'ws/upload/status/(?P<item_id>\d+)/$', UploadStatusConsumer.as_asgi()),
]
