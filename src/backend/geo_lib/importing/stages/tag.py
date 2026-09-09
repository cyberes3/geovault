from geo_lib.importing.context import ImportContext
from geo_lib.importing.runtime import JobRuntime
from geo_lib.importing.support.tagging_step import tag_features


class TagStage:
    name = 'tag'

    def run(self, ctx: ImportContext, runtime: JobRuntime) -> None:
        if ctx.session.options.minimal_processing:
            return
        runtime.checkpoint('before tag')
        log = tag_features(
            ctx.features,
            ctx.session.filename,
            ctx.source_text,
            runtime.check_cancelled,
        )
        ctx.log.extend(log)
        runtime.checkpoint('after tag')
