from geo_lib.importing.context import ImportContext
from geo_lib.importing.runtime import JobRuntime
from geo_lib.processing.elevation_service import fill_missing_elevations
from geo_lib.processing.logging import DatabaseLogLevel, ImportLog
from website.settings_utils import get_required_setting


class ElevateStage:
    name = 'elevate'

    def run(self, ctx: ImportContext, runtime: JobRuntime) -> None:
        runtime.checkpoint('before elevate')
        if not get_required_setting('ELEVATION_API_ENABLED'):
            return
        if not ctx.features:
            return
        elevation_log = ImportLog()
        fill_missing_elevations(
            {'type': 'FeatureCollection', 'features': ctx.features},
            elevation_log,
            is_canceled=runtime.check_cancelled,
        )
        ctx.log.extend(elevation_log)
        runtime.checkpoint('after elevate')
        ctx.log.add("Elevation fill complete", "Elevate", DatabaseLogLevel.DEBUG)
