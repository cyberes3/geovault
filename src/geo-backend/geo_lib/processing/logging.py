import datetime
import json
import logging
import time
import traceback
from contextlib import contextmanager
from enum import Enum
from typing import List
from typing import Optional

from pydantic import BaseModel, Field
from django.utils import timezone


@contextmanager
def timing_context(step_name: str):
    """
    Context manager to track execution time of processing steps.
    
    Usage:
        with timing_context("File validation"):
            # ... processing code ...
    """
    start_time = time.time()
    try:
        yield
    finally:
        end_time = time.time()
        duration = end_time - start_time
        # This will be used by the calling code to log the timing
        return duration


class DatabaseLogLevel(Enum):
    DEBUG = logging.DEBUG
    INFO = logging.INFO
    WARNING = logging.WARNING
    ERROR = logging.ERROR
    CRITICAL = logging.CRITICAL


class DatabaseLogMsg(BaseModel):
    msg: str
    source: str
    timestamp: Optional[datetime.datetime] = None
    level: Optional[DatabaseLogLevel] = Field(DatabaseLogLevel.INFO)


class ImportLog:
    def __init__(self):
        self._messages: List[DatabaseLogMsg] = []

    def add(self, msg: str, source: str, level=DatabaseLogLevel.INFO, duration: float = None):
        assert isinstance(msg, str)
        
        # Add timing information to the message if provided
        if duration is not None:
            timing_info = f" ({duration:.1f}s)"
            msg = msg + timing_info
            
        self._messages.append(DatabaseLogMsg(msg=msg, source=source, level=level))

    def extend(self, msgs: 'ImportLog'):
        for msg in msgs.get():
            self._messages.append(msg)

    def get(self) -> List[DatabaseLogMsg]:
        return self._messages.copy()

    def json(self) -> str:
        return json.dumps([x.model_dump() for x in self._messages])
    
    def add_timing(self, step_name: str, duration: float, source: str = "Processing", level=DatabaseLogLevel.INFO):
        """Add a timing log message for a completed step."""
        self.add(f"{step_name} completed", source, level, duration)


class RealTimeImportLog:
    """
    ImportLog that writes messages to the database immediately when add() is called.
    This allows for real-time log updates during async processing.
    """
    
    def __init__(self, user_id: int, log_id: str = None):
        self._messages: List[DatabaseLogMsg] = []
        self.user_id = user_id
        self.log_id = log_id  # This should be a UUID string
        self._db_logger = logging.getLogger("MAIN").getChild("DBLOG")
    
    def add(self, msg: str, source: str, level=DatabaseLogLevel.INFO, duration: float = None):
        """Add a log message and immediately write it to the database."""
        assert isinstance(msg, str)
        
        # Add timing information to the message if provided
        if duration is not None:
            timing_info = f" ({duration:.1f}s)"
            msg = msg + timing_info
        
        # Generate timestamp once for both in-memory object and database write
        timestamp = timezone.now()
        
        # Create the log message with the timestamp
        log_msg = DatabaseLogMsg(msg=msg, source=source, level=level, timestamp=timestamp)
        self._messages.append(log_msg)
        
        # Write to database immediately with the same timestamp
        try:
            from data.models import DatabaseLogging
            DatabaseLogging.objects.create(
                user_id=self.user_id,
                log_id=self.log_id,
                level=log_msg.level.value,
                text=log_msg.msg,
                source=log_msg.source,
                attributes={},
                timestamp=timestamp,
            )
            self._db_logger.debug(f"Real-time log written: {source} - {msg}")
        except Exception as e:
            self._db_logger.error(f"Failed to write real-time log to database: {str(e)}")
            self._db_logger.error(f"Real-time log database write error traceback: {traceback.format_exc()}")
            # Don't raise the exception - we still want processing to continue
    
    def extend(self, msgs: 'ImportLog'):
        """Extend with messages from another ImportLog and write them to DB."""
        for msg in msgs.get():
            self.add(msg.msg, msg.source, msg.level)
    
    def get(self) -> List[DatabaseLogMsg]:
        """Get all messages (for compatibility with ImportLog)."""
        return self._messages.copy()
    
    def json(self) -> str:
        """Get messages as JSON string."""
        return json.dumps([x.model_dump() for x in self._messages])
    
    def add_timing(self, step_name: str, duration: float, source: str = "Processing", level=DatabaseLogLevel.INFO):
        """Add a timing log message for a completed step."""
        self.add(f"{step_name} completed", source, level, duration)
