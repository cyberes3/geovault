"""
Content decoding helpers for file processors.

Handles BOM stripping and encoding normalization for raw file bytes/strings
before they're handed off to XML parsing or tag generation.
"""
from typing import Any, Optional, Union

from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger('CONTENT_DECODING')


def decode_content(file_data: Union[bytes, str]) -> str:
    """
    Decode file data to a string, stripping a UTF-8 BOM if present.
    """
    if isinstance(file_data, str):
        if file_data.startswith('\ufeff'):
            return file_data[1:]
        return file_data
    return file_data.decode('utf-8-sig')


def normalize_file_data_for_tagging(
    file_data: Optional[Union[bytes, str]],
    filename: str,
    file_type: Optional[Any] = None,
) -> Optional[str]:
    """
    Normalize file_data for tag generators by stripping BOM if present.

    Returns file_data as a string (BOM stripped), or None if decoding fails or
    file_data is None.
    """
    if file_data is None:
        _logger.debug(f"[TAGGING] file_data is None for filename: {filename}")
        return None

    if isinstance(file_data, bytes):
        try:
            decoded = file_data.decode('utf-8-sig')
            _logger.debug(f"[TAGGING] Successfully decoded {len(file_data)} bytes to {len(decoded)} chars for filename: {filename}")
            return decoded
        except UnicodeDecodeError as e:
            is_zip = len(file_data) >= 4 and file_data[:4] == b'PK\x03\x04'
            is_binary = any(
                file_data.startswith(sig)
                for sig in [b'\x1f\x8b', b'PK\x03\x04', b'\x50\x4b\x03\x04']  # gzip, zip
            ) if len(file_data) >= 2 else False

            _logger.warning(
                f"[TAGGING] Failed to decode file_data as UTF-8 for filename: {filename}. "
                f"Size: {len(file_data)} bytes, "
                f"Error: {str(e)}, "
                f"Error position: {e.start}-{e.end}, "
                f"Is ZIP/KMZ: {is_zip}, "
                f"Is binary: {is_binary}, "
                f"File type: {file_type}, "
                f"First 4 bytes (hex): {file_data[:4].hex()}, "
                f"First 100 bytes (hex): {file_data[:100].hex() if len(file_data) >= 100 else file_data.hex()}, "
                f"First 100 bytes (repr): {repr(file_data[:100]) if len(file_data) >= 100 else repr(file_data)}"
            )
            try:
                latin1_decoded = file_data.decode('latin-1')
                _logger.debug("[TAGGING] File can be decoded as latin-1 (but may not be correct)")
                if latin1_decoded.strip().startswith('<?xml') or latin1_decoded.strip().startswith('<'):
                    _logger.warning("[TAGGING] File appears to be XML but failed UTF-8 decode - might be wrong encoding")
            except Exception as decode_err:
                _logger.debug(f"[TAGGING] Even latin-1 decode failed: {decode_err}")
            return None
    else:
        _logger.debug(f"[TAGGING] file_data is already a string ({len(file_data)} chars) for filename: {filename}")
        if file_data.startswith('\ufeff'):
            _logger.debug(f"[TAGGING] Stripping BOM from string for filename: {filename}")
            return file_data[1:]
        return file_data
