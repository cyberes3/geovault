"""Query-string pagination. The only accepted size key is page_size."""

from typing import Any, Mapping

from pydantic import BaseModel, Field, ValidationError


class PageQuery(BaseModel):
    page: int = Field(default=1, ge=1)
    page_size: int = Field(default=10, ge=1)


class PageQueryError(ValueError):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


def parse_page_query(
    params: Mapping[str, Any],
    default_page_size: int = 10,
    max_page_size: int = 100,
) -> PageQuery:
    if "page-size" in params:
        raise PageQueryError("page-size is not accepted; use page_size")
    raw_page = params.get("page", 1)
    raw_size = params.get("page_size", default_page_size)
    try:
        query = PageQuery(page=int(raw_page), page_size=int(raw_size))
    except (TypeError, ValueError, ValidationError) as exc:
        raise PageQueryError("Invalid page or page_size parameter") from exc
    if query.page_size > max_page_size:
        raise PageQueryError(f"page_size cannot exceed {max_page_size}")
    return query
