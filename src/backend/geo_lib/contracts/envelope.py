"""HTTP error and list envelopes. Success bodies are the typed resource itself."""

from math import ceil
from typing import Any, Mapping, Sequence

from pydantic import BaseModel, Field


class ErrorEnvelope(BaseModel):
    error: str
    code: int
    details: dict[str, Any] | None = None


class ListPage(BaseModel):
    items: list[Any]
    page: int = Field(ge=1)
    page_size: int = Field(ge=1)
    total_items: int = Field(ge=0)
    total_pages: int = Field(ge=0)

    @classmethod
    def of(
        cls,
        items: Sequence[Any],
        page: int,
        page_size: int,
        total_items: int,
    ) -> "ListPage":
        total_pages = ceil(total_items / page_size) if page_size > 0 else 0
        return cls(
            items=list(items),
            page=page,
            page_size=page_size,
            total_items=total_items,
            total_pages=total_pages,
        )

    def as_dict(self) -> dict[str, Any]:
        return self.model_dump(mode="json")


def error_body(error: str, code: int, details: Mapping[str, Any] | None = None) -> dict[str, Any]:
    envelope = ErrorEnvelope(error=error, code=code, details=dict(details) if details else None)
    return envelope.model_dump(mode="json", exclude_none=True)
