class ImportStageError(Exception):
    def __init__(self, message: str, unparsable: bool = False):
        super().__init__(message)
        self.message = message
        self.unparsable = unparsable


class ImportCancelled(Exception):
    def __init__(self, stage: str):
        super().__init__(f"Import canceled {stage}")
        self.stage = stage


class ImportTimeout(Exception):
    def __init__(self, stage: str, ceiling_seconds: int):
        super().__init__(f"Import exceeded {ceiling_seconds}s {stage}")
        self.stage = stage
        self.ceiling_seconds = ceiling_seconds


class ImportBatchWriteError(Exception):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message
