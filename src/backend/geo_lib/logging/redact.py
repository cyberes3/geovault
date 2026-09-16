"""Redact sensitive values from loggable query strings."""
from collections.abc import Iterable, Iterator
from urllib.parse import urlencode

SENSITIVE_QUERY_KEYS = frozenset({
    'secret',
    'token',
    'api_key',
    'key',
    'password',
    'pwd',
    'code',
    'share',
})

REDACTED = 'REDACTED'


def query_pairs(query) -> Iterator[tuple[str, str]]:
    """Yield every key/value pair from a Django QueryDict, including repeated keys."""
    for key, values in query.lists():
        for value in values:
            yield key, value


def redact_query_items(items: Iterable[tuple[str, str]]) -> str:
    """Rebuild a query string, replacing values of sensitive keys with REDACTED.

    Keys are matched case-insensitively against SENSITIVE_QUERY_KEYS.
    """
    redacted = [
        (key, REDACTED if key.lower() in SENSITIVE_QUERY_KEYS else value)
        for key, value in items
    ]
    return urlencode(redacted)
