"""
Bulk import job WebSocket module.
"""

from geo_lib.websocket.base_module import BaseWebSocketModule
from geo_lib.processing.jobs import bulk_import_job
from geo_lib.logging.console import get_websocket_logger

logger = get_websocket_logger()


class BulkImportJobModule(BaseWebSocketModule):
    """WebSocket module for bulk import job functionality."""

    @property
    def module_name(self) -> str:
        return "bulk_import_job"

    async def handle_message(self, message_type: str, data: dict) -> None:
        """Handle incoming messages for bulk import job module."""
        if message_type == 'refresh':
            await self.send_initial_state()
        elif message_type == 'start_bulk_import':
            await self.handle_start_bulk_import(data)
        else:
            logger.warning(f"Unknown message type for bulk_import_job module: {message_type}")

    async def handle_start_bulk_import(self, data: dict) -> None:
        """Handle start_bulk_import message."""
        try:
            item_ids = data.get('item_ids', [])
            import_custom_icons = data.get('import_custom_icons', True)

            if not item_ids:
                await self.send_to_client('error', {'message': 'No item IDs provided'})
                return

            if not isinstance(item_ids, list):
                await self.send_to_client('error', {'message': 'item_ids must be a list'})
                return

            # Validate that all IDs are integers
            try:
                item_ids = [int(id) for id in item_ids]
            except (ValueError, TypeError):
                await self.send_to_client('error', {'message': 'All IDs must be integers'})
                return

            # Start the bulk import job
            job_id = bulk_import_job.start_bulk_import_job(
                item_ids=item_ids,
                user_id=self.user.id,
                import_custom_icons=import_custom_icons
            )

            if job_id:
                await self.send_to_client('job_started', {'job_id': job_id, 'item_ids': item_ids})
            else:
                await self.send_to_client('error', {'message': 'Failed to start bulk import job'})

        except Exception as e:
            logger.error(f"Error handling start_bulk_import: {str(e)}")
            await self.send_to_client('error', {'message': f'Error starting bulk import: {str(e)}'})

    async def send_initial_state(self) -> None:
        """Send initial state for bulk import job module."""
        # Bulk import jobs don't have persistent state, so send empty state
        await self.send_to_client('initial_state', {})

    # Bulk import job event handlers
    async def started(self, event):
        """Handle bulk_import_job_started event."""
        await self.send_to_client('started', event['data'])

    async def status_updated(self, event):
        """Handle bulk_import_job_status_updated event."""
        await self.send_to_client('status_updated', event['data'])

    async def completed(self, event):
        """Handle bulk_import_job_completed event."""
        await self.send_to_client('completed', event['data'])

    async def failed(self, event):
        """Handle bulk_import_job_failed event."""
        await self.send_to_client('failed', event['data'])



