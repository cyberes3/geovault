from typing import List

# Tags that the user should not be allowed to edit.
CONST_INTERNAL_TAGS = ['type', 'import-year', 'import-month']


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
