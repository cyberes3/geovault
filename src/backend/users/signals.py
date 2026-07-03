"""
Auth-revocation signal handlers: force-disconnect a user's open WebSocket connections whenever
their session is invalidated, so an already-open socket doesn't keep working after logout.

API key deletion and OAuth token revocation call WebSocketForceDisconnector directly from their
views instead of via a signal, since Django/allauth don't emit a built-in signal for those.
"""
from django.contrib.auth.signals import user_logged_out
from django.dispatch import receiver

from geo_lib.websocket.force_disconnect import WebSocketForceDisconnector


@receiver(user_logged_out)
def force_disconnect_websockets_on_logout(sender, request, user, **kwargs):
    """Fires on any logout path (allauth's LogoutView, admin logout, etc.), since they all call
    through django.contrib.auth.logout(), which sends this signal."""
    if user is not None:
        WebSocketForceDisconnector.disconnect_user(user.id, reason="logout")
