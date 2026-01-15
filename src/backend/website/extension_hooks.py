"""
Extension hook registration system for GeoVault extensions.

This module provides a centralized hook registry that allows extensions to register
callbacks for various platform events. Hooks are automatically prefixed with the
extension name to prevent collisions.

Example usage:
    from website.extension_hooks import register_hook
    
    def my_import_callback(import_item, user_id, created_features):
        # Process imported features
        pass
    
    # In extension_ready() method:
    register_hook('import', 'process_features', my_import_callback)
"""
import logging
from typing import Callable, Dict, List, Tuple, Optional, Any
from threading import Lock

logger = logging.getLogger('website.extension_hooks')

# Registry: hook_type -> List of (full_hook_id, callback, extension_name) tuples
_hook_registry: Dict[str, List[Tuple[str, Callable, str]]] = {}
_registry_lock = Lock()

# Track current extension name during registration (set by ExtensionAppConfig)
_current_extension_name: Optional[str] = None


def set_extension_context(extension_name: str) -> None:
    """
    Set the current extension context for hook registration.
    
    This is called automatically by ExtensionAppConfig when an extension's
    ready() method is executing. Hooks registered during this context will
    be automatically prefixed with the extension name.
    
    Args:
        extension_name: The name of the extension currently initializing
    """
    global _current_extension_name
    _current_extension_name = extension_name


def clear_extension_context() -> None:
    """
    Clear the current extension context.
    
    Called after extension initialization completes.
    """
    global _current_extension_name
    _current_extension_name = None


def register_hook(hook_type: str, hook_id: str, callback: Callable) -> None:
    """
    Register a hook callback for a specific hook type.
    
    The hook_id will be automatically prefixed with the current extension name
    to prevent collisions. For example, if extension "my_ext" registers a hook
    with id "process_data", the full hook ID will be "my_ext.process_data".
    
    Args:
        hook_type: Type of hook (e.g., 'import', 'processing', 'export')
        hook_id: Unique identifier for this hook within the extension
        callback: Callback function to execute when the hook is triggered
        
    Raises:
        ValueError: If hook_type is invalid or extension context is not set
        TypeError: If callback is not callable
        
    Example:
        def my_import_hook(import_item, user_id, created_features):
            # Process after import
            pass
        
        register_hook('import', 'my_import_handler', my_import_hook)
    """
    if not callable(callback):
        raise TypeError(f"Hook callback must be callable, got {type(callback)}")
    
    if _current_extension_name is None:
        raise ValueError(
            "Cannot register hook outside of extension context. "
            "Hooks must be registered in the extension_ready() method of your AppConfig."
        )
    
    # Validate hook_type
    valid_hook_types = ['import', 'processing', 'export']  # Extensible for future types
    if hook_type not in valid_hook_types:
        logger.warning(
            f"Unknown hook type '{hook_type}' registered by extension '{_current_extension_name}'. "
            f"Valid types: {', '.join(valid_hook_types)}"
        )
    
    # Create full hook ID with extension prefix
    full_hook_id = f"{_current_extension_name}.{hook_id}"
    
    with _registry_lock:
        # Initialize hook type registry if needed
        if hook_type not in _hook_registry:
            _hook_registry[hook_type] = []
        
        # Check if hook already exists
        existing_ids = [h_id for h_id, _, _ in _hook_registry[hook_type]]
        if full_hook_id in existing_ids:
            logger.warning(
                f"Hook '{full_hook_id}' (type: {hook_type}) is already registered, replacing existing hook"
            )
            _hook_registry[hook_type] = [
                (h_id, cb, ext_name) for h_id, cb, ext_name in _hook_registry[hook_type]
                if h_id != full_hook_id
            ]
        
        # Register the hook
        _hook_registry[hook_type].append((full_hook_id, callback, _current_extension_name))
        logger.debug(f"Registered {hook_type} hook: {full_hook_id}")


def get_hooks(hook_type: str) -> List[Tuple[str, Callable]]:
    """
    Get all registered hooks of a specific type.
    
    Args:
        hook_type: Type of hook to retrieve
        
    Returns:
        List of (full_hook_id, callback) tuples for the specified hook type
    """
    with _registry_lock:
        hooks = _hook_registry.get(hook_type, [])
        return [(hook_id, callback) for hook_id, callback, _ in hooks]


def execute_hooks(hook_type: str, *args, **kwargs) -> None:
    """
    Execute all registered hooks of a specific type.
    
    Args:
        hook_type: Type of hooks to execute
        *args: Positional arguments to pass to hook callbacks
        **kwargs: Keyword arguments to pass to hook callbacks
        
    Example:
        execute_hooks('import', import_item, user_id, created_features=features)
    """
    hooks = get_hooks(hook_type)
    
    if not hooks:
        return
    
    logger.debug(f"Executing {len(hooks)} {hook_type} hook(s)")
    
    for hook_id, callback in hooks:
        try:
            logger.debug(f"Executing {hook_type} hook: {hook_id}")
            callback(*args, **kwargs)
            logger.debug(f"Hook '{hook_id}' completed successfully")
        except Exception as e:
            # Log error but don't fail the operation
            logger.error(
                f"Error executing {hook_type} hook '{hook_id}': {e}",
                exc_info=True
            )


def get_registered_hooks() -> Dict[str, List[str]]:
    """
    Get all registered hooks grouped by type.
    
    Returns:
        Dictionary mapping hook types to lists of hook IDs
    """
    with _registry_lock:
        return {
            hook_type: [hook_id for hook_id, _, _ in hooks]
            for hook_type, hooks in _hook_registry.items()
        }


def unregister_hook(hook_type: str, hook_id: str) -> bool:
    """
    Unregister a specific hook.
    
    Args:
        hook_type: Type of hook
        hook_id: Full hook ID (including extension prefix)
        
    Returns:
        True if hook was found and removed, False otherwise
    """
    with _registry_lock:
        if hook_type not in _hook_registry:
            return False
        
        original_count = len(_hook_registry[hook_type])
        _hook_registry[hook_type] = [
            (h_id, cb, ext_name) for h_id, cb, ext_name in _hook_registry[hook_type]
            if h_id != hook_id
        ]
        
        removed = len(_hook_registry[hook_type]) < original_count
        if removed:
            logger.debug(f"Unregistered {hook_type} hook: {hook_id}")
        
        return removed
