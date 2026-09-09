from geo_lib.importing.context import ImportContext
from geo_lib.importing.runtime import JobRuntime
from geo_lib.importing.support.track_override import apply_track_name_override


class TrackOverrideStage:
    name = 'track_override'

    def run(self, ctx: ImportContext, runtime: JobRuntime) -> None:
        if ctx.session.options.minimal_processing:
            return
        runtime.checkpoint('before track override')
        log = apply_track_name_override(
            {'type': 'FeatureCollection', 'features': ctx.features},
            ctx.session.filename,
            ctx.session.user_id,
            runtime.job_id,
        )
        ctx.log.extend(log)
