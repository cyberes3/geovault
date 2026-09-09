from geo_lib.importing.context import ImportContext
from geo_lib.importing.errors import ImportStageError
from geo_lib.importing.runtime import JobRuntime
from geo_lib.importing.support.feature_splitting import split_and_validate_features
from geo_lib.processing.logging import DatabaseLogLevel
from website.settings_utils import get_required_setting


class SplitStage:
    name = 'split'

    def run(self, ctx: ImportContext, runtime: JobRuntime) -> None:
        runtime.checkpoint('before split')
        raw_count = len(ctx.features)
        num_threads = get_required_setting('IMPORT_PROCESSING_THREADS')
        processed, log = split_and_validate_features(
            ctx.features,
            num_threads,
            runtime.check_cancelled,
        )
        ctx.log.extend(log)
        runtime.checkpoint('after split')
        if raw_count > 0 and len(processed) == 0:
            raise ImportStageError(
                "File produced no valid features after geometry split",
                unparsable=True,
            )
        ctx.features = processed
        ctx.log.add(f"Split into {len(processed)} features", "Split", DatabaseLogLevel.INFO)
