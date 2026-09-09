from django.test import SimpleTestCase
from django.conf import settings


class TestCacheAndChannelSettings(SimpleTestCase):
    def test_default_cache_is_locmem_shared_is_redis(self):
        self.assertIn("locmem", settings.CACHES["default"]["BACKEND"].lower())
        self.assertIn("redis", settings.CACHES["shared"]["BACKEND"].lower())
        self.assertIn("/5", settings.CACHES["shared"]["LOCATION"])

    def test_channel_layer_sets_capacity_and_group_expiry(self):
        config = settings.CHANNEL_LAYERS["default"]["CONFIG"]
        self.assertIn("capacity", config)
        self.assertIn("group_expiry", config)
        self.assertGreater(config["capacity"], 0)
        self.assertGreater(config["group_expiry"], 0)
