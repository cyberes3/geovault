"""SCAN-only Redis key iteration. Do not use KEYS."""

DEFAULT_SCAN_COUNT = 100


def scan_keys(redis_client, match: str, count: int = DEFAULT_SCAN_COUNT) -> list:
    if hasattr(redis_client, "scan_iter"):
        return list(redis_client.scan_iter(match=match, count=count))
    keys = []
    cursor = 0
    while True:
        cursor, batch = redis_client.scan(cursor=cursor, match=match, count=count)
        keys.extend(batch)
        if cursor == 0:
            break
    return keys


def delete_by_pattern(redis_client, match: str, count: int = DEFAULT_SCAN_COUNT) -> int:
    deleted = 0
    batch = []
    if hasattr(redis_client, "scan_iter"):
        for key in redis_client.scan_iter(match=match, count=count):
            batch.append(key)
            if len(batch) >= count:
                deleted += int(redis_client.delete(*batch))
                batch.clear()
        if batch:
            deleted += int(redis_client.delete(*batch))
        return deleted
    cursor = 0
    while True:
        cursor, keys = redis_client.scan(cursor=cursor, match=match, count=count)
        if keys:
            deleted += int(redis_client.delete(*keys))
        if cursor == 0:
            break
    return deleted
