"""
Registry for extension WebSocket modules. Extensions register their module class here
in extension_ready(); RealtimeConsumer loads them dynamically so extensions are not hardcoded.
"""

from typing import List, Tuple, Type

# (module_name, module_class); module_class must accept (consumer) in __init__
_extension_modules: List[Tuple[str, Type]] = []


def register_websocket_module(name: str, module_class: Type) -> None:
    """Register a WebSocket module for an extension. Call from extension_ready()."""
    if not name or not module_class:
        return
    _extension_modules.append((name, module_class))


def get_registered_websocket_modules() -> List[Tuple[str, Type]]:
    """Return all registered extension WebSocket modules (name, class)."""
    return list(_extension_modules)
