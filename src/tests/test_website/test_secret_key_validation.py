"""
Tests for website.secret_key_validation.require_secret_key.

This is the settings-load-time hard-fail gate: a missing or known-placeholder
SECRET_KEY must abort Django startup entirely (self-hosted app, no "rotate before
prod" safety net), rather than silently falling back to an insecure default.
"""
import pytest
from django.core.exceptions import ImproperlyConfigured

from website.secret_key_validation import require_secret_key


class TestRequireSecretKey:
    def test_accepts_a_real_secret_key(self):
        key = 'a-sufficiently-random-real-secret-key-value'
        assert require_secret_key(key) == key

    @pytest.mark.parametrize('value', [None, '', '   ', '\t\n'])
    def test_rejects_missing_or_blank_key(self, value):
        with pytest.raises(ImproperlyConfigured):
            require_secret_key(value)

    def test_rejects_non_string_key(self):
        with pytest.raises(ImproperlyConfigured):
            require_secret_key(12345)

    @pytest.mark.parametrize('placeholder', [
        'django-insecure-change-this-in-production',
        'django-insecure-f(1zo%f)wm*rl97q0^3!9exd%(s8mz92nagf4q7c2cno&bmyx=',
    ])
    def test_rejects_known_insecure_placeholders(self, placeholder):
        with pytest.raises(ImproperlyConfigured, match='placeholder'):
            require_secret_key(placeholder)

    def test_error_message_for_missing_key_mentions_config_options(self):
        with pytest.raises(ImproperlyConfigured, match='security.secret_key'):
            require_secret_key(None)
