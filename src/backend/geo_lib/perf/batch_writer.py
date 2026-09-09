from typing import Any, Sequence, Type

from django.db import IntegrityError
from django.db.models import Model

from website.settings_utils import get_required_setting


class BatchWriteError(Exception):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class BatchWriter:
    def __init__(self, model: Type[Model], batch_size: int | None = None):
        self.model = model
        self.batch_size = batch_size or get_required_setting('BULK_CREATE_BATCH_SIZE')

    def write(self, objects: Sequence[Any]) -> list[Any]:
        if not objects:
            return []
        try:
            created = self.model.objects.bulk_create(list(objects), batch_size=self.batch_size)
        except IntegrityError as exc:
            raise BatchWriteError(str(exc)) from exc
        return list(created)
