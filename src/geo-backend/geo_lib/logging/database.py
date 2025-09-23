import logging
import os
from enum import Enum

from pydantic import BaseModel

_logger = logging.getLogger("MAIN").getChild("DBLOG")


class DatabaseLogLevel(Enum):
    DEBUG = logging.DEBUG
    INFO = logging.INFO
    WARNING = logging.WARNING
    ERROR = logging.ERROR
    CRITICAL = logging.CRITICAL


class DatabaseLogSource(Enum):
    IMPORT = "import"


class DatabaseLogItem(BaseModel):
    # Using an object so we can add arbitrary data if nessesary.
    msg: str
    level: DatabaseLogLevel


_django_ready = False


def _ensure_django():
    global _django_ready
    if _django_ready:
        return

    # Ensure settings module is configured before importing Django
    if 'DJANGO_SETTINGS_MODULE' not in os.environ:
        os.environ['DJANGO_SETTINGS_MODULE'] = 'website.settings'
    import django
    django.setup()
    _django_ready = True


def log_to_db(msg: str, level: DatabaseLogLevel, user_id: int, source: DatabaseLogSource):
    _logger.log(level.value, msg)
    _ensure_django()
    from data.models import GeoLog  # Imported lazily after Django setup
    GeoLog.objects.create(user_id=user_id, level=level.value, text=msg, source=source.value, type=source.value)
