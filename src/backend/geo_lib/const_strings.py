from typing import List
from geo_lib.processing.tagging import get_internal_tags

# System tag prefixes that identify automatically generated tags.
# These are tags that users should not be allowed to edit.
# Used by tag generators to create system tags and by validation to filter them out.
# This list is now dynamically generated from registered tag generators.
CONST_INTERNAL_TAGS = get_internal_tags()

# Tag priority mapping: prefixes to priority (1-10, with 1 being most important)
# Tags matching these prefixes get the assigned priority. All other tags get priority 0.
TAG_PRIORITIES = {
    'source-file': 1
}


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


def get_tag_priority(tag: str) -> int:
    """
    Get the priority for a tag based on prefix matching.
    
    Tags are matched against TAG_PRIORITIES prefixes (case-insensitive).
    If a tag matches a prefix (exact match or starts with prefix + ':'),
    returns the assigned priority (1-10). Otherwise returns 0 (lowest priority).
    
    Args:
        tag: The tag string to check
        
    Returns:
        Priority value (1-10 if matched, 0 if not matched)
    """
    if not isinstance(tag, str):
        return 0
    
    tag_lower = tag.lower()
    
    # Check each prefix in priority order
    for prefix, priority in TAG_PRIORITIES.items():
        prefix_lower = prefix.lower()
        # Exact match
        if tag_lower == prefix_lower:
            return priority
        # Prefix match (e.g., "type:point" matches "type")
        if tag_lower.startswith(prefix_lower + ':'):
            return priority
    
    # No match found, return 0 (lowest priority)
    return 0
