"""
Tests for BaseWebSocketModule's oversized-payload guard in send_to_client().

This guard exists because a production incident showed that an unbounded/unexpectedly large
data structure (see ImportQueueModule's geofeatures/duplicate_features leak) can slip into a
WebSocket message and crash the whole connection via autobahn's PayloadExceededError. Rather
than relying on every module to bound its own data perfectly, send_to_client() measures the
serialized size and substitutes a small error frame when a message would be unsafe to send.
"""
from unittest.mock import AsyncMock

from django.test import SimpleTestCase

from geo_lib.websocket.base_module import BaseWebSocketModule, _MAX_SAFE_PAYLOAD_BYTES


class _DummyModule(BaseWebSocketModule):
    """Minimal concrete module for exercising send_to_client() in isolation."""

    @property
    def module_name(self) -> str:
        return "dummy"

    async def handle_message(self, message_type, data) -> None:
        pass

    async def send_initial_state(self) -> None:
        pass


def _make_module():
    consumer = AsyncMock()
    consumer.user = None
    consumer.room_group_name = "test_group"
    return _DummyModule(consumer), consumer


class TestSendToClientPayloadGuard(SimpleTestCase):
    """Test that send_to_client() rejects oversized payloads gracefully."""

    async def test_normal_payload_is_sent_unmodified(self):
        module, consumer = _make_module()

        await module.send_to_client('status_updated', {'progress': 50})

        consumer.send.assert_awaited_once()
        sent_text = consumer.send.await_args.kwargs['text_data']
        self.assertIn('"progress": 50', sent_text)
        self.assertIn('"type": "status_updated"', sent_text)

    async def test_oversized_payload_is_replaced_with_error_frame(self):
        module, consumer = _make_module()

        # Comfortably over _MAX_SAFE_PAYLOAD_BYTES.
        oversized_data = {'blob': 'x' * (_MAX_SAFE_PAYLOAD_BYTES + 100_000)}

        await module.send_to_client('initial_state', oversized_data)

        consumer.send.assert_awaited_once()
        sent_text = consumer.send.await_args.kwargs['text_data']
        self.assertLess(len(sent_text.encode('utf-8')), _MAX_SAFE_PAYLOAD_BYTES)
        self.assertIn('"type": "error"', sent_text)
        self.assertIn('"code": 500', sent_text)
        # The oversized payload itself must not have leaked into the error frame.
        self.assertNotIn('blob', sent_text)

    async def test_payload_right_at_the_boundary_is_still_sent(self):
        module, consumer = _make_module()

        # A payload just under the limit should go through as-is, not be replaced.
        data = {'blob': 'x' * (_MAX_SAFE_PAYLOAD_BYTES - 1000)}

        await module.send_to_client('page', data)

        sent_text = consumer.send.await_args.kwargs['text_data']
        self.assertIn('"type": "page"', sent_text)
