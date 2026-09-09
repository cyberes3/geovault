from django.db import transaction

from api.models import ImportQueue
from geo_lib.importing.context import ImportContext
from geo_lib.importing.draft_store import ImportDraftStore
from geo_lib.importing.runtime import JobRuntime
from geo_lib.processing.logging import DatabaseLogLevel
from geo_lib.utils.advisory_locks import advisory_lock
from geo_lib.utils.feature_utils import build_feature_type_summary


class PersistDraftStage:
    name = 'persist_draft'

    def run(self, ctx: ImportContext, runtime: JobRuntime) -> None:
        runtime.checkpoint('before persist draft')
        queue = ImportQueue.objects.get(id=ctx.session.queue_id)
        ctx.type_summary = build_feature_type_summary(ctx.features)
        with transaction.atomic():
            runtime.checkpoint('before persist draft lock')
            with advisory_lock(queue.user_id, ctx.session.raw_file.file_hash):
                store = ImportDraftStore(queue)
                count = store.replace_features(ctx.features, ctx.verdicts, ctx.skip_intent)
                queue.file_hash = ctx.session.raw_file.file_hash
                queue.raw_file = ctx.session.raw_file.persist_text()
                queue.raw_file_encoding = ctx.session.raw_file.encoding
                queue.queue_status = ImportQueue.STATUS_READY
                queue.unparsable = False
                queue.save(update_fields=[
                    'file_hash', 'raw_file', 'raw_file_encoding', 'queue_status', 'unparsable',
                ])
        ctx.log.add(
            f"Persisted {count} draft features ({ctx.type_summary})",
            "PersistDraft",
            DatabaseLogLevel.INFO,
        )
