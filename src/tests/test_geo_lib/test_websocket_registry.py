"""
Tests for geo_lib.websocket.registry (extension WebSocket module registration).
"""
from unittest.mock import MagicMock

from django.test import TestCase

from geo_lib.websocket.registry import (
    get_registered_websocket_modules,
    register_websocket_module,
)


class DummyModule:
    """Minimal module class that accepts consumer in __init__."""

    def __init__(self, consumer):
        self.consumer = consumer


class TestWebSocketRegistry(TestCase):
    """Test register_websocket_module and get_registered_websocket_modules."""

    def test_get_registered_returns_list_of_tuples(self):
        """get_registered_websocket_modules returns a list of (name, class) tuples."""
        result = get_registered_websocket_modules()
        self.assertIsInstance(result, list)
        for item in result:
            self.assertIsInstance(item, tuple)
            self.assertEqual(len(item), 2)
            self.assertIsInstance(item[0], str)
            self.assertTrue(callable(item[1]) or hasattr(item[1], "__init__"))

    def test_register_with_empty_name_does_not_add(self):
        """register_websocket_module with empty name does not append to registry."""
        before = get_registered_websocket_modules()
        register_websocket_module("", DummyModule)
        after = get_registered_websocket_modules()
        self.assertEqual(len(after), len(before))

    def test_register_with_none_class_does_not_add(self):
        """register_websocket_module with None module_class does not append."""
        before = get_registered_websocket_modules()
        register_websocket_module("none_class_mod", None)
        after = get_registered_websocket_modules()
        self.assertEqual(len(after), len(before))

    def test_register_adds_module(self):
        """register_websocket_module with valid name and class adds to registry."""
        before = get_registered_websocket_modules()
        unique_name = "_test_websocket_registry_dummy_"
        register_websocket_module(unique_name, DummyModule)
        after = get_registered_websocket_modules()
        self.assertEqual(len(after), len(before) + 1)
        names = [name for name, _ in after]
        self.assertIn(unique_name, names)
        by_name = dict(after)
        self.assertIs(by_name[unique_name], DummyModule)
