"""
Import hook system for post-import callbacks.

This module provides a simple hook system that allows registering callbacks
to be executed after successful imports. The design is basic now but structured
to grow into a plugin system later (similar to TagGenerator pattern).

Hooks receive the import_item, user_id, and list of created FeatureStore objects.
"""
from typing import Callable, List, Tuple, Optional
from geo_lib.logging.console import get_tagged_logger
from api.models import ImportQueue, FeatureStore

_logger = get_tagged_logger('ImportHooks')

# Registry of import hooks: List of (hook_id, callback) tuples
_import_hooks: List[Tuple[str, Callable]] = []


def register_import_hook(hook_id: str, callback: Callable) -> None:
    """
    Register a hook function to be called after successful imports.
    
    Args:
        hook_id: Unique identifier for this hook (for logging/debugging)
        callback: Callback function with signature:
                  callback(import_item: ImportQueue, user_id: int, created_features: List[FeatureStore]) -> None
    """
    if not callable(callback):
        raise TypeError(f"Hook callback for '{hook_id}' must be callable")
    
    # Check if hook_id already exists
    existing_ids = [hook_id for h_id, _ in _import_hooks]
    if hook_id in existing_ids:
        _logger.warning(f"Hook '{hook_id}' is already registered, replacing existing hook")
        _import_hooks[:] = [(h_id, cb) for h_id, cb in _import_hooks if h_id != hook_id]
    
    _import_hooks.append((hook_id, callback))
    _logger.debug(f"Registered import hook: {hook_id}")


def execute_import_hooks(
    import_item: ImportQueue,
    user_id: int,
    created_features: Optional[List[FeatureStore]] = None
) -> None:
    """
    Execute all registered import hooks.
    
    Args:
        import_item: The ImportQueue item that was imported
        user_id: ID of the user who imported the item
        created_features: List of FeatureStore objects that were created (may be empty)
    """
    if created_features is None:
        created_features = []
    
    if not _import_hooks:
        return  # No hooks registered
    
    _logger.debug(f"Executing {len(_import_hooks)} import hook(s) for import_item {import_item.id}")
    
    for hook_id, callback in _import_hooks:
        try:
            _logger.debug(f"Executing hook '{hook_id}' for import_item {import_item.id}")
            callback(import_item, user_id, created_features)
            _logger.debug(f"Hook '{hook_id}' completed successfully")
        except Exception as e:
            # Log error but don't fail the import
            _logger.error(
                f"Error executing import hook '{hook_id}' for import_item {import_item.id}: {e}",
                exc_info=True
            )


def get_registered_hooks() -> List[str]:
    """
    Get list of registered hook IDs (for debugging/monitoring).
    
    Returns:
        List of hook IDs
    """
    return [hook_id for hook_id, _ in _import_hooks]

