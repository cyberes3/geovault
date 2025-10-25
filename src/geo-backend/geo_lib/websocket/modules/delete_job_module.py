"""
Delete job WebSocket module.
"""

import logging

from geo_lib.websocket.base_module import BaseWebSocketModule

logger = logging.getLogger(__name__)


class DeleteJobModule(BaseWebSocketModule):
    """WebSocket module for delete job functionality."""

    @property
    def module_name(self) -> str:
        return "delete_job"

    async def handle_message(self, message_type: str, data: dict) -> None:
        """Handle incoming messages for delete job module."""
        if message_type == 'refresh':
            await self.send_initial_state()
        else:
            logger.warning(f"Unknown message type for delete_job module: {message_type}")

    async def send_initial_state(self) -> None:
        """Send initial state for delete job module."""
        # Delete jobs don't have persistent state, so send empty state
        await self.send_to_client('initial_state', {})

    # Delete job event handlers
    async def started(self, event):
        """Handle delete_job_started event."""
        await self.send_to_client('started', event['data'])

    async def status_updated(self, event):
        """Handle delete_job_status_updated event."""
        await self.send_to_client('status_updated', event['data'])

    async def completed(self, event):
        """Handle delete_job_completed event."""
        await self.send_to_client('completed', event['data'])

    async def failed(self, event):
        """Handle delete_job_failed event."""
        await self.send_to_client('failed', event['data'])
