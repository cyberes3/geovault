import logging

from data.models import DatabaseLogging
from geo_lib.processing.logging import ImportLog

_logger = logging.getLogger("MAIN").getChild("DBLOG")


def importlog_to_db(log_obj: ImportLog, user_id: int, log_id: str = None):
    attributes = {}
    if log_id:
        attributes['log_id'] = log_id

    for msg in log_obj.get():
        DatabaseLogging.objects.create(
            user_id=user_id,
            level=msg.level.value,
            text=msg.msg,
            source=msg.source,
            attributes=attributes,
            timestamp=msg.timestamp,
        )
