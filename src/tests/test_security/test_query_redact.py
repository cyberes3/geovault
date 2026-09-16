"""Tests for geo_lib.logging.redact."""
from django.http import QueryDict

from geo_lib.logging.redact import REDACTED, query_pairs, redact_query_items


class TestRedactQueryItems:
    def test_redacts_secret(self):
        result = redact_query_items([('secret', 'supersecret'), ('id', 'abc')])
        assert 'supersecret' not in result
        assert f'secret={REDACTED}' in result
        assert 'id=abc' in result

    def test_redacts_share_token(self):
        result = redact_query_items([('share', 'share-token-value'), ('format', 'kmz')])
        assert 'share-token-value' not in result
        assert f'share={REDACTED}' in result
        assert 'format=kmz' in result

    def test_redacts_keys_case_insensitively(self):
        result = redact_query_items([('TOKEN', 'leak-me'), ('q', 'ok')])
        assert 'leak-me' not in result
        assert f'TOKEN={REDACTED}' in result
        assert 'q=ok' in result

    def test_leaves_unrelated_params_unchanged(self):
        result = redact_query_items([('page', '2'), ('bbox', '1,2,3,4')])
        assert result == 'page=2&bbox=1%2C2%2C3%2C4'

    def test_empty_items(self):
        assert redact_query_items([]) == ''

    def test_querydict_keeps_repeated_keys(self):
        query = QueryDict('tags=a&tags=b&secret=supersecret')
        result = redact_query_items(query_pairs(query))
        assert result == f'tags=a&tags=b&secret={REDACTED}'
        assert 'supersecret' not in result
