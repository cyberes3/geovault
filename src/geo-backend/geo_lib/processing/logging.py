import datetime
import json
from typing import List
from typing import Optional

from pydantic import BaseModel, Field


class ImportLogMsg(BaseModel):
    timestamp: Optional[str] = Field(default_factory=lambda: datetime.datetime.now(datetime.timezone.utc).isoformat())
    msg: str


class ImportLog:
    def __init__(self):
        self._messages: List[ImportLogMsg] = []

    def add(self, msg: str):
        assert isinstance(msg, str)
        self._messages.append(ImportLogMsg(msg=msg))

    def extend(self, msgs: 'ImportLog'):
        for msg in msgs.get():
            self._messages.append(msg)

    def get(self) -> List[ImportLogMsg]:
        return self._messages.copy()

    def json(self) -> str:
        return json.dumps([x.model_dump() for x in self._messages])
