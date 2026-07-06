"""
Tests for the shared app-level WebSocket ping/pong helper (geo_lib.websocket.ping_pong), used by
RealtimeConsumer, ProcessStatusConsumer, and LiveTrackOnlyConsumer.
"""
import json

from django.test import SimpleTestCase

from geo_lib.websocket.ping_pong import is_ping_message, pong_payload


class TestIsPingMessage(SimpleTestCase):
    def test_recognizes_ping(self):
        self.assertTrue(is_ping_message({"type": "ping"}))

    def test_recognizes_ping_with_module_and_data(self):
        self.assertTrue(is_ping_message({"module": "live_track", "type": "ping", "data": {}}))

    def test_rejects_other_types(self):
        self.assertFalse(is_ping_message({"type": "pong"}))
        self.assertFalse(is_ping_message({"type": "refresh"}))
        self.assertFalse(is_ping_message({}))

    def test_rejects_non_dict_payloads(self):
        self.assertFalse(is_ping_message(None))
        self.assertFalse(is_ping_message("ping"))
        self.assertFalse(is_ping_message(["ping"]))


class TestPongPayload(SimpleTestCase):
    def test_without_module(self):
        payload = json.loads(pong_payload())
        self.assertEqual(payload, {"type": "pong", "data": {}})

    def test_with_module(self):
        payload = json.loads(pong_payload(module="live_track"))
        self.assertEqual(payload, {"type": "pong", "data": {}, "module": "live_track"})
