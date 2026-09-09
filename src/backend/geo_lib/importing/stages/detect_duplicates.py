from api.models import ImportQueue
from geo_lib.duplicates.adapters.import_draft import build_duplicate_index
from geo_lib.duplicates.detector import DuplicateDetector
from geo_lib.duplicates.skip_intent import SkipIntent
from geo_lib.importing.context import ImportContext
from geo_lib.importing.runtime import JobRuntime
from geo_lib.processing.logging import DatabaseLogLevel


class DetectDuplicatesStage:
    name = 'detect_duplicates'

    def run(self, ctx: ImportContext, runtime: JobRuntime) -> None:
        if ctx.session.options.minimal_processing:
            ctx.verdicts = {}
            ctx.skip_intent = SkipIntent()
            return
        runtime.checkpoint('before detect duplicates')
        queue = ImportQueue.objects.get(id=ctx.session.queue_id)
        detector = DuplicateDetector()
        unique, dropped = detector.dedupe_internal(ctx.features)
        ctx.internal_duplicate_count = dropped
        ctx.features = unique
        if dropped:
            ctx.log.add(
                f"Dropped {dropped} exact in-file copies",
                "Duplicate Detection",
                DatabaseLogLevel.INFO,
            )
        index = build_duplicate_index(
            ctx.session.user_id,
            ctx.features,
            ctx.session.queue_id,
            queue.timestamp,
        )
        ctx.verdicts = detector.detect_two_pass(ctx.features, index)
        prior = SkipIntent.from_stored(queue.skip_intent)
        ctx.skip_intent = SkipIntent.from_verdicts(ctx.verdicts, prior)
        hash_count = sum(1 for v in ctx.verdicts.values() if v.kind.value == 'hash')
        geom_count = sum(1 for v in ctx.verdicts.values() if v.kind.value == 'geometry')
        ctx.log.add(
            f"Duplicate verdicts: {hash_count} hash, {geom_count} geometry",
            "Duplicate Detection",
            DatabaseLogLevel.INFO,
        )
        runtime.checkpoint('after detect duplicates')
