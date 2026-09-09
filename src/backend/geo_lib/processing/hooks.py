"""
Post-import provider execution.

Import callbacks are ImportProvider instances registered from extension_ready().
created_features is the source of truth after finalize.
"""
from typing import List, Optional

from api.models import ImportQueue, FeatureStore
from website.extensions.import_provider import execute_import_providers


def execute_import_hooks(
    import_item: ImportQueue,
    user_id: int,
    created_features: Optional[List[FeatureStore]] = None,
) -> None:
    """
    Run every registered ImportProvider.on_import_finalized.

    Args:
        import_item: The ImportQueue row for this import
        user_id: User who performed the import
        created_features: FeatureStore rows created by the import (may be empty)
    """
    if created_features is None:
        created_features = []
    execute_import_providers(import_item, user_id, created_features)
