from geo_lib.importing.context import ImportContext
from geo_lib.importing.errors import ImportStageError
from geo_lib.importing.runtime import JobRuntime


class ValidateStage:
    name = 'validate'

    def run(self, ctx: ImportContext, runtime: JobRuntime) -> None:
        runtime.checkpoint('before validate')
        if not ctx.features:
            raise ImportStageError("No features to validate", unparsable=True)
