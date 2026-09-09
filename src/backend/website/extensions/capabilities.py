"""
Runtime capability registry for first-party extensions.

One registry: well-known paths, websocket routes (read as a function at ASGI
build), background tasks, and periodic schedules. Import providers live in
import_provider.py. Dashboard widgets are declared on ExtensionManifest.
"""
import logging
from enum import Enum
from threading import Lock
from typing import Any, Callable, Dict, List, Optional, Tuple, Type

from celery import current_app

logger = logging.getLogger("website.extensions.capabilities")

_well_known_registry: Dict[str, Tuple[Callable, str]] = {}
_websocket_routes: List[Tuple[str, Type[Any], str]] = []
_bg_task_registry: Dict[str, Dict[str, Any]] = {}
_periodic_bg_task_registry: Dict[str, Dict[str, Any]] = {}
_registry_lock = Lock()
_current_extension_name: Optional[str] = None


class ExtensionCapability(str, Enum):
    IMPORT_PROVIDER = "import_provider"
    WELL_KNOWN = "well_known"
    WEBSOCKET = "websocket"
    BG_TASK = "bg_task"
    PERIODIC = "periodic"
    DASHBOARD_WIDGET = "dashboard_widget"


def set_extension_context(extension_name: str) -> None:
    global _current_extension_name
    _current_extension_name = extension_name


def clear_extension_context() -> None:
    global _current_extension_name
    _current_extension_name = None


def current_extension_name() -> Optional[str]:
    return _current_extension_name


def register_well_known(path: str, callback: Callable) -> None:
    """Register a .well-known item. Path is relative to .well-known/."""
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
            if existing_ext != _current_extension_name:
                raise ValueError(
                    f".well-known path '{path}' is already registered by extension '{existing_ext}'. "
                    f"Extension '{_current_extension_name}' cannot register the same path."
                )

        _well_known_registry[path] = (callback, _current_extension_name)
        logger.debug("Registered .well-known item: %s (extension: %s)", path, _current_extension_name)


def get_well_known_callback(path: str) -> Optional[Callable]:
    with _registry_lock:
        item = _well_known_registry.get(path)
        return item[0] if item else None


def register_websocket_route(path_regex: str, consumer_class: Type[Any]) -> None:
    """
    Register a WebSocket route. Call from extension_ready().

    Path must start with ws/extensions/. The regex is used as-is in Django re_path.
    Routes are read via websocket_routes() at ASGI build, not snapshotted at import.
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
        logger.debug(
            "Registered WebSocket route: %s (extension: %s)",
            path_regex,
            _current_extension_name,
        )


def websocket_routes() -> List[Tuple[str, Type[Any]]]:
    """Return current extension WebSocket routes. Evaluate at ASGI build."""
    with _registry_lock:
        return [(path_regex, consumer_class) for path_regex, consumer_class, _ in _websocket_routes]


def get_registered_websocket_routes() -> List[Tuple[str, Type[Any]]]:
    return websocket_routes()


def register_bg_task(
    task_id: str,
    callback: Callable,
    *,
    queue: Optional[str] = None,
    bind: bool = False,
    time_limit: Optional[int] = None,
    soft_time_limit: Optional[int] = None,
    autoretry_for: Optional[Tuple[Type[Exception], ...]] = None,
    retry_kwargs: Optional[Dict[str, Any]] = None,
) -> str:
    """
    Register an extension background task with Celery.

    Call once from extension_ready() — not via a module-level @shared_task on the
    callback — so the task is bound to a single callback reference.

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
    if time_limit:
        task_options["time_limit"] = time_limit
    if soft_time_limit:
        task_options["soft_time_limit"] = soft_time_limit
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
            "time_limit": time_limit,
            "soft_time_limit": soft_time_limit,
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

    Collected during extension_ready(); ExtensionRuntime binds them to Celery
    after every extension_ready() has run.
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
    with _registry_lock:
        return list(_periodic_bg_task_registry.values())
