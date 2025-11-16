"""
Upload job WebSocket module.
Handles upload job processing events (status updates, completion, etc.).
"""

from geo_lib.websocket.base_module import BaseWebSocketModule
from geo_lib.logging.console import get_websocket_logger

logger = get_websocket_logger()


class UploadJobModule(BaseWebSocketModule):
    """WebSocket module for upload job processing events."""
    
    @property
    def module_name(self) -> str:
        return "upload_job"
    
    async def handle_message(self, message_type: str, data: dict) -> None:
        """Handle incoming messages for upload job module."""
        if message_type == 'refresh':
            # Upload job module doesn't need to send initial state
            # The import queue module handles that
            pass
        else:
            logger.warning(f"Unknown message type for upload_job module: {message_type}")
    
    async def send_initial_state(self) -> None:
        """Upload job module doesn't send initial state - import queue module handles that."""
        # Upload job module doesn't need to send initial state
        # The import queue module handles the initial state for the UI
        pass
    
    # WebSocket event handlers for upload job events
    async def status_updated(self, event):
        """Handle upload job status updates."""
        await self.send_to_client('status_updated', event['data'])
    
    async def completed(self, event):
        """Handle upload job completion."""
        await self.send_to_client('completed', event['data'])
    
    async def failed(self, event):
        """Handle upload job failure."""
        await self.send_to_client('failed', event['data'])
