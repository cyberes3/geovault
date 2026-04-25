"""
Post-import hook execution.

Import callbacks are registered only via ``website.extensions.extension_hooks``
(``register_hook('import', ...)`` in an extension's ``extension_ready()``).
This module forwards execution to that registry.
"""
from typing import List, Optional

from api.models import ImportQueue, FeatureStore
from website.extensions.extension_hooks import execute_hooks


def execute_import_hooks(
    import_item: ImportQueue,
    user_id: int,
    created_features: Optional[List[FeatureStore]] = None,
) -> None:
    """
    Run all registered ``import`` hooks from the extension hook registry.

    Args:
        import_item: The ImportQueue row for this import
        user_id: User who performed the import
        created_features: FeatureStore rows created by the import (may be empty)
    """
    if created_features is None:
        created_features = []
    execute_hooks("import", import_item, user_id, created_features=created_features)
