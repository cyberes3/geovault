"""Helpers for encoding raw uploaded file bytes for ImportQueue storage."""

import base64
import hashlib


def encode_raw_file_data(raw_file_data: bytes | str) -> tuple[str, str]:
    """
    Encode raw file data and compute its hash.

    Returns:
        Tuple of (file_content_string, file_hash)
        - file_content_string: UTF-8 decoded string or base64 encoded string for binary data
        - file_hash: SHA256 hash of the raw file content
    """
    if isinstance(raw_file_data, str):
        raw_bytes = raw_file_data.encode('utf-8')
    else:
        raw_bytes = raw_file_data

    file_hash = hashlib.sha256(raw_bytes).hexdigest()

    if isinstance(raw_file_data, bytes):
        try:
            file_content = raw_bytes.decode('utf-8')
        except UnicodeDecodeError:
            file_content = base64.b64encode(raw_bytes).decode('utf-8')
    else:
        file_content = raw_file_data

    return file_content, file_hash
