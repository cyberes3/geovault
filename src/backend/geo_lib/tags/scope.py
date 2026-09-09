"""Tag index scope and namespace constants."""

NAMESPACE_USER = 'user'
NAMESPACE_SYSTEM = 'system'

NAMESPACES = (NAMESPACE_USER, NAMESPACE_SYSTEM)


def index_scope(feature_scope: str | None) -> str | None:
    """Store FeatureStore.scope on FeatureTag (NULL means main_map)."""
    if feature_scope is None or feature_scope == '':
        return None
    return feature_scope


def is_main_map_scope(scope: str | None) -> bool:
    return scope is None or scope == ''
