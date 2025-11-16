"""
Base module for WebSocket realtime functionality.
"""

from abc import ABC, abstractmethod
from typing import Dict, Any, Optional

from geo_lib.logging.console import get_websocket_logger

logger = get_websocket_logger()


class BaseWebSocketModule(ABC):
    """Abstract base class for WebSocket modules."""
    
    def __init__(self, consumer):
        """Initialize the module with a reference to the consumer."""
        self.consumer = consumer
        self.user = consumer.user
        self.room_group_name = consumer.room_group_name
    
    @property
    @abstractmethod
    def module_name(self) -> str:
        """Return the module name (used for routing messages)."""
        pass
    
    @abstractmethod
    async def handle_message(self, message_type: str, data: Dict[str, Any]) -> None:
        """Handle incoming messages for this module."""
        pass
    
    @abstractmethod
    async def send_initial_state(self) -> None:
        """Send initial state for this module."""
        pass
    
    async def send_to_client(self, message_type: str, data: Dict[str, Any]) -> None:
        """Send a message to the client."""
        await self.consumer.send(text_data=self.consumer.encode_json({
            'module': self.module_name,
            'type': message_type,
            'data': data
        }))
    
