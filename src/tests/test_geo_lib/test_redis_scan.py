from django.test import SimpleTestCase

from geo_lib.utils.redis_scan import delete_by_pattern, scan_keys


class _FakeRedis:
    def __init__(self, keys):
        self._keys = list(keys)
        self.deleted = []

    def scan_iter(self, match=None, count=None):
        prefix = match[:-1] if match and match.endswith("*") else match
        for key in self._keys:
            if prefix is None or key.startswith(prefix):
                yield key

    def delete(self, *keys):
        self.deleted.extend(keys)
        return len(keys)


class _ScanOnlyRedis:
    def __init__(self, keys):
        self._keys = list(keys)
        self.deleted = []

    def scan(self, cursor=0, match=None, count=None):
        prefix = match[:-1] if match and match.endswith("*") else match
        matched = [key for key in self._keys if prefix is None or key.startswith(prefix)]
        return 0, matched

    def delete(self, *keys):
        self.deleted.extend(keys)
        return len(keys)


class TestRedisScan(SimpleTestCase):
    def test_scan_keys_uses_scan_iter(self):
        client = _FakeRedis(["job:1", "other:2", "job:3"])
        self.assertEqual(scan_keys(client, "job:*"), ["job:1", "job:3"])

    def test_scan_keys_falls_back_to_scan(self):
        client = _ScanOnlyRedis(["user_jobs:1", "job:9"])
        self.assertEqual(scan_keys(client, "user_jobs:*"), ["user_jobs:1"])

    def test_delete_by_pattern(self):
        client = _FakeRedis(["job:1", "job:2", "keep"])
        deleted = delete_by_pattern(client, "job:*", count=1)
        self.assertEqual(deleted, 2)
        self.assertEqual(client.deleted, ["job:1", "job:2"])
