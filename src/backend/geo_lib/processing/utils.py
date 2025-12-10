"""
Utility functions for processing operations.

This module contains helper functions for processing tasks including
file encoding, feature hash injection, and duplicate handling.
"""

import base64
import hashlib
from typing import Dict, List

from geo_lib.feature_id import generate_geojson_hash
from geo_lib.processing.duplicate_detection.models import DuplicateMatchType


def encode_raw_file_data(raw_file_data: bytes | str) -> tuple[str, str]:
    """
    Encode raw file data and compute its hash.
    
    Args:
        raw_file_data: Raw file data as bytes or string
        
    Returns:
        Tuple of (file_content_string, file_hash)
        - file_content_string: UTF-8 decoded string or base64 encoded string for binary data
        - file_hash: SHA256 hash of the raw file content
    """
    # Ensure we have bytes for hashing
    if isinstance(raw_file_data, str):
        raw_bytes = raw_file_data.encode('utf-8')
    else:
        raw_bytes = raw_file_data

    # Compute hash
    file_hash = hashlib.sha256(raw_bytes).hexdigest()

    # Convert to string for storage
    if isinstance(raw_file_data, bytes):
        # Try to decode as UTF-8, fall back to base64 if it's binary
        try:
            file_content = raw_bytes.decode('utf-8')
        except UnicodeDecodeError:
            # For binary files like KMZ, store as base64
            file_content = base64.b64encode(raw_bytes).decode('utf-8')
    else:
        file_content = raw_file_data

    return file_content, file_hash


def inject_feature_hashes(features: List[Dict]) -> None:
    """
    Pre-calculate and inject geojson_hash into feature properties (in-place).
    
    This ensures that the hash used for duplicate detection is preserved
    and not affected by Pydantic serialization differences later.
    
    Args:
        features: List of GeoJSON features to inject hashes into
    """
    for feature in features:
        geojson_hash = generate_geojson_hash(feature)
        if 'properties' not in feature or feature['properties'] is None:
            feature['properties'] = {}
        feature['properties']['geojson_hash'] = geojson_hash


def build_skipped_feature_ids(duplicate_features: List[Dict],
                              existing_skipped: set) -> List[str]:
    """
    Build list of feature IDs to auto-skip (geometry duplicates only).
    
    Hash duplicates are always blocked and should not be in skipped_feature_ids.
    Only geometry duplicates can be user-controllable via the skipped list.
    
    Args:
        duplicate_features: List of duplicate feature info dicts
        existing_skipped: Set of already skipped feature IDs
        
    Returns:
        List of feature IDs (hashes) to skip
    """
    skipped_ids = set(existing_skipped)

    # Only add geometry duplicates to skipped list
    for dup in duplicate_features:
        if dup.get('match_type') == DuplicateMatchType.GEOMETRY:
            dup_feature = dup.get('feature')
            if dup_feature:
                geojson_hash = dup_feature.get('properties', {}).get('geojson_hash')
                if not geojson_hash:
                    geojson_hash = generate_geojson_hash(dup_feature)
                skipped_ids.add(geojson_hash)

    return list(skipped_ids)
