"""Verify Gitea webhook HMAC-SHA256 signatures."""
import hashlib
import hmac


def verify_gitea_signature(secret: str, body: bytes, signature_header: str) -> bool:
    """True if `signature_header` is the hex HMAC-SHA256 of `body` under `secret`."""
    if not signature_header:
        return False
    expected = hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(signature_header, expected)
