from typing import List

# System tag prefixes that identify automatically generated tags.
# These are tags that users should not be allowed to edit.
# Used by tag generators to create system tags and by validation to filter them out.
CONST_INTERNAL_TAGS = [
    'type',
    'import-year',
    'import-month',
    'feature-year',
    'feature-month',
    'source-file',
    'is-track',
    'elevation',
    'geocoding'
]


def is_protected_tag(tag: str, protected_prefixes: List[str]) -> bool:
    """
    Check if a tag is protected (matches exactly or starts with a protected prefix).
    
    Args:
        tag: The tag to check
        protected_prefixes: List of protected tag prefixes (e.g., ['type', 'import-year'])
    
    Returns:
        True if the tag is protected, False otherwise
    """
    if not isinstance(tag, str):
        return False
    
    for prefix in protected_prefixes:
        # Exact match
        if tag == prefix:
            return True
        # Prefix match (e.g., "type:point" matches "type")
        if tag.startswith(prefix + ':'):
            return True
    
    return False


def filter_protected_tags(tags: List[str], protected_prefixes: List[str]) -> List[str]:
    """
    Filter out protected tags from a list of tags.
    
    Args:
        tags: List of tags to filter
        protected_prefixes: List of protected tag prefixes
    
    Returns:
        List of tags with protected tags removed
    """
    if not isinstance(tags, list):
        return []
    
    return [tag for tag in tags if not is_protected_tag(tag, protected_prefixes)]


def prepare_user_tags(tags: List[str]) -> List[str]:
    """
    Prepare user tags by converting to lowercase and deduplicating.
    Preserves order (first occurrence kept).
    
    Args:
        tags: List of tag strings
        
    Returns:
        List of unique lowercase tags in original order
    """
    if not tags or not isinstance(tags, list):
        return []
    
    # Use dict.fromkeys() to deduplicate while preserving order (Python 3.7+)
    # Convert to lowercase first, then deduplicate using dict (single data structure)
    unique_tags = dict.fromkeys(tag.lower() for tag in tags if tag)
    return list(unique_tags)
