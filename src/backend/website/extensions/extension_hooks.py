"""
Extension hook registration system for GeoVault extensions.

This module provides a centralized hook registry that allows extensions to register
callbacks for various platform events. Hooks are automatically prefixed with the
extension name to prevent collisions.

Example usage:
    from website.extensions.extension_hooks import register_hook
    
    def my_import_callback(import_item, user_id, created_features):
        # Process imported features
        pass
    
    # In extension_ready() method:
    register_hook('import', 'process_features', my_import_callback)
"""
import logging
from threading import Lock
from typing import Callable, Dict, List, Tuple, Optional, Type, Any

from celery import current_app

logger = logging.getLogger('website.extension_hooks')

# Registry: hook_type -> List of (full_hook_id, callback, extension_name) tuples
_hook_registry: Dict[str, List[Tuple[str, Callable, str]]] = {}
# Registry: well_known_path -> (callback, extension_name)
_well_known_registry: Dict[str, Tuple[Callable, str]] = {}
# Registry: (path_regex, consumer_class) for extension WebSocket routes (base path ws/extensions/)
_websocket_routes: List[Tuple[str, Type[Any], str]] = []  # (path_regex, consumer_class, extension_name)
# Registry: task_name -> task metadata
_bg_task_registry: Dict[str, Dict[str, Any]] = {}
# Registry: schedule_name -> periodic schedule metadata
_periodic_bg_task_registry: Dict[str, Dict[str, Any]] = {}
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
    """
    if not callable(callback):
        raise TypeError(f"Hook callback must be callable, got {type(callback)}")

    if _current_extension_name is None:
        raise ValueError(
            "Cannot register hook outside of extension context. "
            "Hooks must be registered in the extension_ready() method of your AppConfig."
        )

    # Validate hook_type
    valid_hook_types = ['import']
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


def register_well_known(path: str, callback: Callable) -> None:
    """
    Register a .well-known item.
    Path should be relative to .well-known/, e.g. 'assetlinks.json'.
    
    Args:
        path: The path relative to .well-known/
        callback: Django view function to handle the request
        
    Raises:
        ValueError: If path is already registered by another extension or 
                   if called outside of extension context.
    """
    if not callable(callback):
        raise TypeError(f"Well-known callback must be callable, got {type(callback)}")

    if _current_extension_name is None:
        raise ValueError(
            "Cannot register .well-known item outside of extension context. "
            "Register items in the extension_ready() method of your AppConfig."
        )

    with _registry_lock:
        if path in _well_known_registry:
            _, existing_ext = _well_known_registry[path]
            # If it's the same extension, we allow replacing it (for hot-reloading)
            if existing_ext != _current_extension_name:
                raise ValueError(
                    f".well-known path '{path}' is already registered by extension '{existing_ext}'. "
                    f"Extension '{_current_extension_name}' cannot register the same path."
                )

        # Register the well-known item
        _well_known_registry[path] = (callback, _current_extension_name)
        logger.debug(f"Registered .well-known item: {path} (extension: {_current_extension_name})")


def get_well_known_callback(path: str) -> Optional[Callable]:
    """
    Get the callback for a registered .well-known path.
    """
    with _registry_lock:
        item = _well_known_registry.get(path)
        return item[0] if item else None


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


def register_websocket_route(path_regex: str, consumer_class: Type[Any]) -> None:
    """
    Register a WebSocket route for an extension. Call from extension_ready().

    Path must be under the extension WebSocket base: ws/extensions/<extension_name>/...
    The path_regex is used as-is in Django's re_path (e.g. r'ws/extensions/live-track/trackers-live/$').

    Args:
        path_regex: Regex pattern for the WebSocket path (e.g. r'ws/extensions/live-track/trackers-live/$').
        consumer_class: ASGI consumer class (e.g. AsyncWebsocketConsumer subclass).

    Raises:
        ValueError: If called outside of extension context.
    """
    if _current_extension_name is None:
        raise ValueError(
            "Cannot register WebSocket route outside of extension context. "
            "Register in the extension_ready() method of your AppConfig."
        )
    if not path_regex.startswith("ws/extensions/"):
        raise ValueError(
            f"WebSocket path must start with 'ws/extensions/'; got {path_regex!r}"
        )
    with _registry_lock:
        _websocket_routes.append((path_regex, consumer_class, _current_extension_name))
        logger.debug(f"Registered WebSocket route: {path_regex} (extension: {_current_extension_name})")


def get_registered_websocket_routes() -> List[Tuple[str, Type[Any]]]:
    """
    Return all registered extension WebSocket routes as (path_regex, consumer_class) tuples.
    Used by api.routing to build websocket_urlpatterns.
    """
    with _registry_lock:
        return [(path_regex, consumer_class) for path_regex, consumer_class, _ in _websocket_routes]


def register_bg_task(
    task_id: str,
    callback: Callable,
    *,
    queue: Optional[str] = None,
    bind: bool = False,
    autoretry_for: Optional[Tuple[Type[Exception], ...]] = None,
    retry_kwargs: Optional[Dict[str, Any]] = None,
) -> str:
    """
    Register an extension background task with Celery.

    Returns:
        Fully qualified Celery task name.
    """
    if not callable(callback):
        raise TypeError(f"Background task callback must be callable, got {type(callback)}")

    if _current_extension_name is None:
        raise ValueError(
            "Cannot register background task outside of extension context. "
            "Register tasks in the extension_ready() method of your AppConfig."
        )

    full_task_id = f"{_current_extension_name}.{task_id}"
    task_name = f"extensions.{full_task_id}"
    task_options: Dict[str, Any] = {"name": task_name, "bind": bind}
    if queue:
        task_options["queue"] = queue
    if autoretry_for:
        task_options["autoretry_for"] = autoretry_for
    if retry_kwargs:
        task_options["retry_kwargs"] = retry_kwargs

    celery_task = current_app.tasks.get(task_name)
    if celery_task is None:
        celery_task = current_app.task(**task_options)(callback)

    with _registry_lock:
        _bg_task_registry[task_name] = {
            "task_name": task_name,
            "extension_name": _current_extension_name,
            "task_id": task_id,
            "callback": callback,
            "queue": queue,
            "bind": bind,
            "autoretry_for": autoretry_for or tuple(),
            "retry_kwargs": retry_kwargs or {},
            "celery_task": celery_task,
        }

    logger.debug("Registered background task: %s", task_name)
    return task_name


def register_periodic_bg_task(
    schedule_id: str,
    task_ref: Any,
    schedule: Any,
    args: Optional[List[Any]] = None,
    kwargs: Optional[Dict[str, Any]] = None,
    options: Optional[Dict[str, Any]] = None,
) -> str:
    """
    Register a periodic schedule for an extension background task.

    task_ref can be:
      - fully qualified task name string
      - Celery task object (with .name)
    """
    if _current_extension_name is None:
        raise ValueError(
            "Cannot register periodic background task outside of extension context. "
            "Register schedules in the extension_ready() method of your AppConfig."
        )

    if isinstance(task_ref, str):
        task_name = task_ref
    elif hasattr(task_ref, "name"):
        task_name = task_ref.name
    else:
        raise TypeError("task_ref must be a task name string or Celery task object")

    schedule_name = f"extensions.{_current_extension_name}.{schedule_id}"
    with _registry_lock:
        _periodic_bg_task_registry[schedule_name] = {
            "schedule_name": schedule_name,
            "extension_name": _current_extension_name,
            "schedule_id": schedule_id,
            "task_name": task_name,
            "schedule": schedule,
            "args": args or [],
            "kwargs": kwargs or {},
            "options": options or {},
        }
    logger.debug("Registered periodic background task: %s -> %s", schedule_name, task_name)
    return schedule_name


def get_registered_bg_tasks() -> List[Dict[str, Any]]:
    """Return registered extension background tasks."""
    with _registry_lock:
        return [
            {
                "task_name": item["task_name"],
                "extension_name": item["extension_name"],
                "task_id": item["task_id"],
                "queue": item["queue"],
                "bind": item["bind"],
            }
            for item in _bg_task_registry.values()
        ]


def get_registered_periodic_bg_tasks() -> List[Dict[str, Any]]:
    """Return registered periodic background task schedules."""
    with _registry_lock:
        return list(_periodic_bg_task_registry.values())
