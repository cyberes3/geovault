"""
Import hook system for post-import callbacks.

This module provides a simple hook system that allows registering callbacks
to be executed after successful imports. The design is basic now but structured
to grow into a plugin system later (similar to TagGenerator pattern).

Hooks receive the import_item, user_id, and list of created FeatureStore objects.

This module now integrates with the extension hooks system (website.extension_hooks)
for better organization and automatic extension name prefixing. The legacy
register_import_hook() function is maintained for backward compatibility.
"""
from typing import Callable, List, Tuple, Optional
from geo_lib.logging.console import get_tagged_logger
from api.models import ImportQueue, FeatureStore

_logger = get_tagged_logger('ImportHooks')

# Legacy registry for backward compatibility: List of (hook_id, callback) tuples
_import_hooks: List[Tuple[str, Callable]] = []


def register_import_hook(hook_id: str, callback: Callable) -> None:
    """
    Register a hook function to be called after successful imports.
    
    This function is maintained for backward compatibility. For new extensions,
    use the extension hooks system instead:
    
        from website.extension_hooks import register_hook
        register_hook('import', 'my_hook_id', callback)
    
    Args:
        hook_id: Unique identifier for this hook (for logging/debugging)
        callback: Callback function with signature:
                  callback(import_item: ImportQueue, user_id: int, created_features: List[FeatureStore]) -> None
    """
    if not callable(callback):
        raise TypeError(f"Hook callback for '{hook_id}' must be callable")
    
    # Try to use the extension hooks system if available
    try:
        from website.extensions.extension_hooks import register_hook, _current_extension_name
        
        # If we're in an extension context, use the new system
        if _current_extension_name is not None:
            register_hook('import', hook_id, callback)
            _logger.debug(f"Registered import hook via extension system: {hook_id}")
            return
    except ImportError:
        # Extension hooks system not available, fall back to legacy
        pass
    except Exception as e:
        _logger.warning(f"Failed to register via extension hooks system, using legacy: {e}")
    
    # Legacy registration (for non-extension code or when extension system unavailable)
    existing_ids = [hook_id for h_id, _ in _import_hooks]
    if hook_id in existing_ids:
        _logger.warning(f"Hook '{hook_id}' is already registered, replacing existing hook")
        _import_hooks[:] = [(h_id, cb) for h_id, cb in _import_hooks if h_id != hook_id]
    
    _import_hooks.append((hook_id, callback))
    _logger.debug(f"Registered import hook (legacy): {hook_id}")


def execute_import_hooks(
    import_item: ImportQueue,
    user_id: int,
    created_features: Optional[List[FeatureStore]] = None
) -> None:
    """
    Execute all registered import hooks.
    
    This function executes both:
    1. Hooks registered via the extension hooks system (website.extension_hooks)
    2. Legacy hooks registered via register_import_hook()
    
    Args:
        import_item: The ImportQueue item that was imported
        user_id: ID of the user who imported the item
        created_features: List of FeatureStore objects that were created (may be empty)
    """
    if created_features is None:
        created_features = []
    
    # Execute hooks from extension hooks system
    try:
        from website.extensions.extension_hooks import execute_hooks
        execute_hooks('import', import_item, user_id, created_features=created_features)
    except ImportError:
        # Extension hooks system not available, skip
        pass
    except Exception as e:
        _logger.warning(f"Error executing extension import hooks: {e}", exc_info=True)
    
    # Execute legacy hooks
    if not _import_hooks:
        return  # No legacy hooks registered
    
    _logger.debug(f"Executing {len(_import_hooks)} legacy import hook(s) for import_item {import_item.id}")
    
    for hook_id, callback in _import_hooks:
        try:
            _logger.debug(f"Executing legacy hook '{hook_id}' for import_item {import_item.id}")
            callback(import_item, user_id, created_features)
            _logger.debug(f"Legacy hook '{hook_id}' completed successfully")
        except Exception as e:
            # Log error but don't fail the import
            _logger.error(
                f"Error executing legacy import hook '{hook_id}' for import_item {import_item.id}: {e}",
                exc_info=True
            )


def get_registered_hooks() -> List[str]:
    """
    Get list of registered hook IDs (for debugging/monitoring).
    
    Returns both extension hooks and legacy hooks.
    
    Returns:
        List of hook IDs
    """
    hook_ids = [hook_id for hook_id, _ in _import_hooks]
    
    # Also include extension hooks if available
    try:
        from website.extensions.extension_hooks import get_hooks
        extension_hooks = get_hooks('import')
        hook_ids.extend([hook_id for hook_id, _ in extension_hooks])
    except ImportError:
        pass
    except Exception:
        pass
    
    return hook_ids

