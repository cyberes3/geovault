from geo_lib.importing.apply_plan import ApplyPlan
from geo_lib.importing.context import ImportContext
from geo_lib.importing.runtime import JobRuntime
from geo_lib.importing.session import UploadSession
from geo_lib.importing.stages.apply import apply_queue
from geo_lib.importing.stages.decode import DecodeStage
from geo_lib.importing.stages.detect_duplicates import DetectDuplicatesStage
from geo_lib.importing.stages.elevate import ElevateStage
from geo_lib.importing.stages.hash import HashStage
from geo_lib.importing.stages.persist_draft import PersistDraftStage
from geo_lib.importing.stages.split import SplitStage
from geo_lib.importing.stages.tag import TagStage
from geo_lib.importing.stages.track_override import TrackOverrideStage
from geo_lib.importing.stages.validate import ValidateStage


class ImportStage:
    name: str

    def run(self, ctx: ImportContext, runtime: JobRuntime) -> None:
        raise NotImplementedError


PROCESS_STAGES = (
    DecodeStage(),
    SplitStage(),
    ValidateStage(),
    ElevateStage(),
    TagStage(),
    TrackOverrideStage(),
    HashStage(),
    DetectDuplicatesStage(),
    PersistDraftStage(),
)


class ImportPipeline:
    def process(self, session: UploadSession, runtime: JobRuntime) -> ImportContext:
        ctx = ImportContext(session=session)
        for stage in PROCESS_STAGES:
            runtime.checkpoint(f'before {stage.name}')
            runtime.report_progress(f'Running {stage.name}...', _progress_for(stage.name))
            stage.run(ctx, runtime)
        return ctx

    def apply(self, plan: ApplyPlan, runtime: JobRuntime) -> dict:
        return apply_queue(plan, runtime)


def _progress_for(stage_name: str) -> float:
    return {
        'decode': 20.0,
        'split': 36.0,
        'validate': 42.0,
        'elevate': 55.0,
        'tag': 70.0,
        'track_override': 76.0,
        'hash': 82.0,
        'detect_duplicates': 90.0,
        'persist_draft': 96.0,
    }.get(stage_name, 50.0)
