"""
WebSocket consumers for real-time import queue updates.
"""

import json
import logging
from channels.generic.websocket import AsyncWebsocketConsumer
from channels.db import database_sync_to_async
from django.contrib.auth.models import AnonymousUser
from django.core.serializers.json import DjangoJSONEncoder
from data.models import ImportQueue
from geo_lib.processing.status_tracker import status_tracker

logger = logging.getLogger(__name__)


class ImportQueueConsumer(AsyncWebsocketConsumer):
    """WebSocket consumer for import queue updates."""

    async def connect(self):
        """Handle WebSocket connection."""
        # Get user from scope
        self.user = self.scope["user"]
        
        # Reject connection if user is not authenticated
        if isinstance(self.user, AnonymousUser):
            await self.close()
            return

        # Create user-specific room group
        self.room_group_name = f"import_queue_{self.user.id}"
        
        # Join room group
        await self.channel_layer.group_add(
            self.room_group_name,
            self.channel_name
        )
        
        await self.accept()
        
        # Send initial state
        await self.send_initial_state()

    async def disconnect(self, close_code):
        """Handle WebSocket disconnection."""
        # Leave room group
        await self.channel_layer.group_discard(
            self.room_group_name,
            self.channel_name
        )

    async def receive(self, text_data):
        """Handle messages received from WebSocket."""
        try:
            data = json.loads(text_data)
            message_type = data.get('type')
            
            if message_type == 'ping':
                await self.send(text_data=json.dumps({'type': 'pong'}))
            elif message_type == 'refresh':
                await self.send_initial_state()
        except json.JSONDecodeError:
            logger.warning(f"Invalid JSON received from user {self.user.id}")

    async def send_initial_state(self):
        """Send the current import queue state to the client."""
        try:
            # Get current import queue data
            queue_data = await self.get_import_queue_data()
            
            # Send initial state
            await self.send(text_data=json.dumps({
                'type': 'initial_state',
                'data': queue_data
            }))
        except Exception as e:
            logger.error(f"Error sending initial state to user {self.user.id}: {str(e)}")
            await self.send(text_data=json.dumps({
                'type': 'error',
                'message': 'Failed to load import queue data'
            }))

    @database_sync_to_async
    def get_import_queue_data(self):
        """Get current import queue data for the user."""
        # Get user items from database
        user_items = ImportQueue.objects.filter(
            user=self.user, 
            imported=False
        ).order_by('-timestamp').values(
            'id', 'geofeatures', 'original_filename', 'geojson_hash', 
            'log_id', 'timestamp', 'imported', 'unparsable'
        )
        
        data = json.loads(json.dumps(list(user_items), cls=DjangoJSONEncoder))
        
        # Get all active processing jobs for this user
        user_jobs = status_tracker.get_user_jobs(self.user.id)
        active_job_ids = {
            job.import_queue_id for job in user_jobs 
            if job.status.value == 'processing' and job.import_queue_id
        }
        
        # Build a map of geojson_hash to items for duplicate detection
        hash_to_items = {}
        for item in data:
            if item.get('geojson_hash'):
                if item['geojson_hash'] not in hash_to_items:
                    hash_to_items[item['geojson_hash']] = []
                hash_to_items[item['geojson_hash']].append(item)
        
        # Check for imported files with same hash
        imported_hashes = {}
        if hash_to_items:
            imported_items = ImportQueue.objects.filter(
                user=self.user,
                imported=True,
                geojson_hash__in=list(hash_to_items.keys())
            ).values('geojson_hash', 'original_filename')
            
            for imported_item in imported_items:
                imported_hashes[imported_item['geojson_hash']] = imported_item['original_filename']
        
        # Process each item
        for i, item in enumerate(data):
            count = len(item['geofeatures'])
            
            # Check if this item is currently being processed
            item['processing'] = item['id'] in active_job_ids
            
            # Also consider items with empty geofeatures as processing if they were created recently
            if not item['processing'] and count == 0 and not item.get('unparsable'):
                from django.utils import timezone
                from datetime import timedelta
                
                # If item was created within the last 10 seconds, consider it as processing
                item_timestamp = item['timestamp']
                if isinstance(item_timestamp, str):
                    from datetime import datetime
                    item_timestamp = datetime.fromisoformat(item_timestamp.replace('Z', '+00:00'))
                
                time_since_creation = timezone.now() - item_timestamp
                if time_since_creation < timedelta(seconds=10):
                    item['processing'] = True
            
            # Check if there's an error in the geofeatures or if marked as unparsable
            if item.get('unparsable') or (count == 1 and item['geofeatures'] and isinstance(item['geofeatures'][0], dict) and 'error' in item['geofeatures'][0]):
                item['feature_count'] = 0
                item['processing_failed'] = True
            elif count == 0 and item['processing']:
                item['feature_count'] = -1  # Special value to indicate processing
                item['processing_failed'] = False
            else:
                item['feature_count'] = count
                item['processing_failed'] = False
            
            # Check for duplicate status
            item['duplicate_status'] = None
            if item.get('geojson_hash'):
                geojson_hash = item['geojson_hash']
                items_with_same_hash = hash_to_items.get(geojson_hash, [])
                
                # Check if there are other items in queue with same hash (uploaded earlier)
                earlier_items = [
                    other for other in items_with_same_hash
                    if other['id'] != item['id'] and other['timestamp'] < item['timestamp']
                ]
                
                if earlier_items:
                    item['duplicate_status'] = 'duplicate_in_queue'
                elif geojson_hash in imported_hashes:
                    item['duplicate_status'] = 'duplicate_imported'
            
            # Remove keys from response as they're not needed by frontend
            del item['geofeatures']
            del item['log_id']
            del item['geojson_hash']
            del item['unparsable']
        
        return data

    # WebSocket event handlers
    async def item_added(self, event):
        """Handle item_added event."""
        await self.send(text_data=json.dumps({
            'type': 'item_added',
            'data': event['data']
        }))

    async def item_deleted(self, event):
        """Handle item_deleted event."""
        await self.send(text_data=json.dumps({
            'type': 'item_deleted',
            'data': event['data']
        }))

    async def items_deleted(self, event):
        """Handle items_deleted event."""
        await self.send(text_data=json.dumps({
            'type': 'items_deleted',
            'data': event['data']
        }))

    async def status_updated(self, event):
        """Handle status_updated event."""
        await self.send(text_data=json.dumps({
            'type': 'status_updated',
            'data': event['data']
        }))

    async def item_imported(self, event):
        """Handle item_imported event."""
        await self.send(text_data=json.dumps({
            'type': 'item_imported',
            'data': event['data']
        }))
