from typing import Any, Dict, Optional

from channels.db import database_sync_to_async

from api.models import DatabaseLogging, ImportQueue
from geo_lib.importing.draft_store import ImportDraftStore, serialize_page
from geo_lib.processing.jobs.helpers.status_tracker import status_tracker
from geo_lib.processing.messages import ERROR_TYPE_FILE_UNPARSABLE, PROCESSING_FAILED_WITH_LOGS
from geo_lib.websocket.base_module import BaseWebSocketModule

INITIAL_LOG_TAIL = 100


class ProcessStatusModule(BaseWebSocketModule):
    """WebSocket module for process status updates for a specific import item."""

    def __init__(self, consumer, import_item):
        super().__init__(consumer)
        self.import_item = import_item
        self.current_page = 1
        self.current_page_size = 50
        self.hide_duplicates = False

    @property
    def module_name(self) -> str:
        return "process_status"

    async def send_initial_state(self) -> None:
        get_item = database_sync_to_async(ImportQueue.objects.get)
        self.import_item = await get_item(id=self.import_item.id)

        file_duplicate = await database_sync_to_async(self._file_duplicate_info)()
        if file_duplicate['status'] == 'duplicate_in_queue':
            message = (
                "This upload is a duplicate of '" + file_duplicate['original_filename']
                if file_duplicate['original_filename'] else
                "This upload is a duplicate."
            )
            await self.send_to_client('error', {
                'code': 409,
                'message': message,
                'file_duplicate': file_duplicate,
                'item_id': self.import_item.id
            })
            return

        is_processing = False
        job_details = None
        if not self.import_item.imported and not self.import_item.unparsable:
            user_jobs = status_tracker.get_user_jobs(self.user.id)
            for job in user_jobs:
                if job.import_queue_id == self.import_item.id and job.status.value == 'processing':
                    is_processing = True
                    job_details = status_tracker.get_job_status(job.job_id)
                    break

        features_data = await self._get_paginated_features(
            self.current_page, self.current_page_size, self.hide_duplicates
        )
        logs_data, has_more_logs = await self._fetch_logs(limit=INITIAL_LOG_TAIL)
        await self.send_to_client('initial_state', {
            'item_id': self.import_item.id,
            'imported': self.import_item.imported,
            'unparsable': self.import_item.unparsable,
            'original_filename': self.import_item.original_filename,
            'timestamp': self.import_item.timestamp.isoformat() if self.import_item.timestamp else None,
            'processing': is_processing,
            'job_details': job_details,
            'features': features_data,
            'logs': logs_data,
            'has_more_logs': has_more_logs,
            'file_duplicate': file_duplicate,
        })

    async def send_logs(self, after_id: Optional[int] = None, before_id: Optional[int] = None) -> None:
        limit = INITIAL_LOG_TAIL if before_id is not None and after_id is None else None
        logs_data, has_more_logs = await self._fetch_logs(
            after_id=after_id, before_id=before_id, limit=limit,
        )
        await self.send_to_client('logs', {
            'logs': logs_data,
            'after_id': after_id,
            'before_id': before_id,
            'has_more_logs': has_more_logs,
        })

    async def send_page(self, page: int, page_size: int, hide_duplicates: bool = False) -> None:
        self.current_page = page
        self.current_page_size = page_size
        self.hide_duplicates = hide_duplicates
        features_data = await self._get_paginated_features(page, page_size, hide_duplicates)
        await self.send_to_client('page', features_data)

    async def handle_status_updated(self, data: Dict[str, Any]) -> None:
        await self.send_to_client('status_updated', data)

    async def handle_logs_added(self, data: Dict[str, Any]) -> None:
        await self.send_to_client('log_added', data)

    async def handle_logs_batch_added(self, data: Dict[str, Any]) -> None:
        await self.send_to_client('logs_batch_added', data)

    async def handle_item_completed(self, data: Dict[str, Any]) -> None:
        await self.send_to_client('item_completed', data)

    async def handle_item_failed(self, data: Dict[str, Any]) -> None:
        await self.send_to_client('item_failed', data)

    async def handle_item_deleted(self, data: Dict[str, Any]) -> None:
        await self.send_to_client('item_deleted', data)

    async def handle_page(self, data: Dict[str, Any]) -> None:
        await self.send_to_client('page', data)

    async def handle_duplicates_updated(self, data: Dict[str, Any]) -> None:
        get_item = database_sync_to_async(ImportQueue.objects.get)
        self.import_item = await get_item(id=self.import_item.id)
        features_data = await self._get_paginated_features(
            self.current_page, self.current_page_size, self.hide_duplicates
        )
        await self.send_to_client('page', features_data)

    def _file_duplicate_info(self) -> dict:
        file_duplicate = {'status': None, 'original_filename': None}
        if self.import_item.file_hash:
            duplicate_in_queue = ImportQueue.objects.filter(
                user_id=self.user.id,
                file_hash=self.import_item.file_hash,
                imported=False,
                timestamp__lt=self.import_item.timestamp
            ).order_by('timestamp').first()
            if duplicate_in_queue:
                file_duplicate['status'] = 'duplicate_in_queue'
                file_duplicate['original_filename'] = duplicate_in_queue.original_filename
                return file_duplicate
            duplicate_imported = ImportQueue.objects.filter(
                user_id=self.user.id,
                file_hash=self.import_item.file_hash,
                imported=True
            ).order_by('timestamp').first()
            if duplicate_imported:
                file_duplicate['status'] = 'duplicate_imported'
                file_duplicate['original_filename'] = duplicate_imported.original_filename
                return file_duplicate
        store = ImportDraftStore(self.import_item)
        if store.feature_count() > 0 and store.queryset(hide_duplicates=True).count() == 0:
            file_duplicate['status'] = 'all_features_duplicate'
        return file_duplicate

    async def _get_paginated_features(
        self, page: int, page_size: int, hide_duplicates: bool = False
    ) -> Dict[str, Any]:
        return await database_sync_to_async(self._paginated_features_sync)(
            page, page_size, hide_duplicates
        )

    def _paginated_features_sync(self, page: int, page_size: int, hide_duplicates: bool) -> Dict[str, Any]:
        if self.import_item.imported:
            return {
                'data': [],
                'pagination': {
                    'page': 1,
                    'page_size': page_size,
                    'total_features': 0,
                    'total_pages': 0,
                    'has_next': False,
                    'has_previous': False
                },
                'duplicate_counts': {'hash': 0, 'geometry': 0},
                'skip_intent': {'skipped': [], 'restored': []},
                'feature_count': 0,
            }
        if self.import_item.unparsable:
            return {
                'data': [{'error': ERROR_TYPE_FILE_UNPARSABLE, 'message': PROCESSING_FAILED_WITH_LOGS}],
                'pagination': {
                    'page': 1,
                    'page_size': page_size,
                    'total_features': 1,
                    'total_pages': 1,
                    'has_next': False,
                    'has_previous': False
                },
                'duplicate_counts': {'hash': 0, 'geometry': 0},
                'skip_intent': {'skipped': [], 'restored': []},
                'feature_count': 0,
            }
        store = ImportDraftStore(self.import_item)
        return serialize_page(store.page(page, page_size, hide_duplicates=hide_duplicates))

    def _serialize_logs(self, db_logs: list) -> list:
        return [{
            'id': log.id,
            'timestamp': log.timestamp.isoformat(),
            'msg': log.text,
            'source': log.source,
            'level': log.level
        } for log in db_logs]

    async def _fetch_logs(
        self,
        after_id: Optional[int] = None,
        before_id: Optional[int] = None,
        limit: Optional[int] = None,
    ) -> tuple[list, bool]:
        if not self.import_item.log_id:
            return [], False

        def get_logs():
            query = DatabaseLogging.objects.filter(log_id=self.import_item.log_id)
            if after_id is not None:
                return list(query.filter(id__gt=after_id).order_by('id')), False
            if before_id is not None:
                older = query.filter(id__lt=before_id)
                if limit is None:
                    return list(older.order_by('id')), False
                total_older = older.count()
                logs = list(older.order_by('-id')[:limit])
                logs.reverse()
                return logs, total_older > len(logs)
            if limit is not None:
                total = query.count()
                logs = list(query.order_by('-id')[:limit])
                logs.reverse()
                return logs, total > limit
            return list(query.order_by('id')), False

        db_logs, has_more = await database_sync_to_async(get_logs)()
        return self._serialize_logs(db_logs), has_more
