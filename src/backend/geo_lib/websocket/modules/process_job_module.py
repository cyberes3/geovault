from geo_lib.logging.console import get_websocket_logger
from geo_lib.websocket.base_module import BaseWebSocketModule

logger = get_websocket_logger()

"""
Process job WebSocket module.
Handles process job processing events (status updates, completion, etc.).
"""


class ProcessJobModule(BaseWebSocketModule):
    """WebSocket module for process job processing events."""

    @property
    def module_name(self) -> str:
        return "process_job"

    async def handle_message(self, message_type: str, data: dict) -> None:
        """Handle incoming messages for process job module."""
        if message_type == 'refresh':
            # Process job module doesn't need to send initial state
            # The import queue module handles that
            pass
        else:
            logger.warning(f"Unknown message type for process_job module: {message_type}")

    async def send_initial_state(self) -> None:
        """Process job module doesn't send initial state - import queue module handles that."""
        # Process job module doesn't need to send initial state
        # The import queue module handles the initial state for the UI
        pass

    # WebSocket event handlers for process job events
    async def status_updated(self, event):
        """Handle process job status updates."""
        await self.send_to_client('status_updated', event['data'])

    async def completed(self, event):
        """Handle process job completion."""
        await self.send_to_client('completed', event['data'])

    async def failed(self, event):
        """Handle process job failure."""
        await self.send_to_client('failed', event['data'])
