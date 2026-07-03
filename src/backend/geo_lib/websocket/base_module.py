"""
Base module for WebSocket realtime functionality.
"""
import json
from abc import ABC, abstractmethod
from typing import Dict, Any

from geo_lib.logging.console import get_tagged_logger

logger = get_tagged_logger('websocket')

# Production Daphne is started with a 10 MiB WebSocket message/frame limit (see
# server-prod.sh); this leaves a small margin below that so we can detect and gracefully
# reject an oversized payload ourselves instead of autobahn's PayloadExceededError killing
# the whole connection outright.
_MAX_SAFE_PAYLOAD_BYTES = int(9.9 * 1024 * 1024)  # 9.9 MiB


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
        """
        Send a message to the client.

        Guards against oversized payloads: a bug that lets some unbounded dataset (e.g. an
        internal-only field, or an unpaginated query) leak into a message would otherwise crash
        the whole WebSocket connection with autobahn's PayloadExceededError. Here we measure the
        serialized size first and substitute a small error frame if it's unsafe, so one bad
        message degrades gracefully instead of dropping the client's entire realtime connection.
        """
        payload = json.dumps({
            'module': self.module_name,
            'type': message_type,
            'data': data
        })
        payload_size = len(payload.encode('utf-8'))
        if payload_size > _MAX_SAFE_PAYLOAD_BYTES:
            logger.error(
                f"Refusing to send oversized WebSocket message: module={self.module_name} "
                f"type={message_type} size={payload_size} bytes (limit {_MAX_SAFE_PAYLOAD_BYTES})"
            )
            payload = json.dumps({
                'module': self.module_name,
                'type': 'error',
                'data': {'code': 500, 'message': 'Server response too large to send. Please try again.'}
            })
        await self.consumer.send(text_data=payload)
