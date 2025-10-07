import datetime
import json
import logging
from enum import Enum
from typing import List
from typing import Optional

from pydantic import BaseModel, Field


class DatabaseLogLevel(Enum):
    DEBUG = logging.DEBUG
    INFO = logging.INFO
    WARNING = logging.WARNING
    ERROR = logging.ERROR
    CRITICAL = logging.CRITICAL


class DatabaseLogMsg(BaseModel):
    msg: str
    source: str
    timestamp: Optional[datetime.datetime] = Field(default_factory=lambda: datetime.datetime.now(datetime.timezone.utc))
    level: Optional[DatabaseLogLevel] = Field(DatabaseLogLevel.INFO)


class ImportLog:
    def __init__(self):
        self._messages: List[DatabaseLogMsg] = []

    def add(self, msg: str, source: str, level=DatabaseLogLevel.INFO):
        assert isinstance(msg, str)
        self._messages.append(DatabaseLogMsg(msg=msg, source=source, level=level))

    def extend(self, msgs: 'ImportLog'):
        for msg in msgs.get():
            self._messages.append(msg)

    def get(self) -> List[DatabaseLogMsg]:
        return self._messages.copy()

    def json(self) -> str:
        return json.dumps([x.model_dump() for x in self._messages])
