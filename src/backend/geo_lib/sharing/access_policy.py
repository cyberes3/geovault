from abc import ABC, abstractmethod
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Protocol

from geo_lib.sharing.constants import (
    ACTION_FEATURES,
    ACTION_TRACK,
    AUDIENCE_AUTHENTICATED,
    AUDIENCE_WORLD,
    CAP_ALLOW_DOWNLOADS,
    CAP_INCLUDE_TAGS,
    CAP_SCOPE_FILTER,
    DOMAIN_MAP,
    TAG_MODE_NONE,
    TAG_MODE_USER,
)

class AccessRole(StrEnum):
    OWNER = "owner"
    DIRECT_SHAREE = "direct_sharee"
    GROUP_SHAREE = "group_sharee"
    AUTH_PUBLIC = "auth_public"
    WORLD = "world"
    NONE = "none"


AUTH_VIEWER_ROLES = frozenset({
    AccessRole.DIRECT_SHAREE,
    AccessRole.GROUP_SHAREE,
    AccessRole.AUTH_PUBLIC,
})

ACCOUNT_PII_KEYS = frozenset({
    "owner_email",
    "shared_with_emails",
    "email",
    "user_id",
    "owner_id",
    "grantee_user_id",
})


class ShareLinkLike(Protocol):
    token: str
    domain: str
    subject_kind: str
    subject_ref: Any
    audience: str
    capabilities: Mapping[str, Any]
    revoked_at: Any
    owner_id: int


@dataclass
class AccessContext:
    user: Any = None
    visibility: str | None = None
    is_owner: bool = False
    is_grantee: bool = False


def _is_authenticated(user: Any) -> bool:
    return bool(user is not None and getattr(user, "is_authenticated", False))


def _link_active(link: ShareLinkLike) -> bool:
    return link.revoked_at is None


def strip_account_pii(payload: Any) -> Any:
    if isinstance(payload, dict):
        return {
            key: strip_account_pii(value)
            for key, value in payload.items()
            if key not in ACCOUNT_PII_KEYS
        }
    if isinstance(payload, list):
        return [strip_account_pii(item) for item in payload]
    return payload


class AccessPolicy(ABC):
    @abstractmethod
    def can_discover(self, link: ShareLinkLike, context: AccessContext) -> bool:
        raise NotImplementedError

    @abstractmethod
    def can_read_data(self, link: ShareLinkLike, context: AccessContext) -> bool:
        raise NotImplementedError

    def redact(self, payload: dict[str, Any]) -> dict[str, Any]:
        return strip_account_pii(payload)

    def scope_filter(self, link: ShareLinkLike) -> dict[str, Any]:
        raw = link.capabilities.get(CAP_SCOPE_FILTER) if link.capabilities else None
        return dict(raw) if isinstance(raw, Mapping) else {}

    def tag_mode(self, link: ShareLinkLike) -> str:
        if bool((link.capabilities or {}).get(CAP_INCLUDE_TAGS)):
            return TAG_MODE_USER
        return TAG_MODE_NONE

    def download_allowed(self, link: ShareLinkLike) -> bool:
        return bool((link.capabilities or {}).get(CAP_ALLOW_DOWNLOADS))

    def counts_access(self, action: str) -> bool:
        return action in {ACTION_FEATURES, ACTION_TRACK}

    def include_user_tags(self, link: ShareLinkLike) -> bool:
        return self.tag_mode(link) == TAG_MODE_USER


class MapWorldPolicy(AccessPolicy):
    """Map links are world-only. Possession of an active token is authorization."""

    def can_discover(self, link: ShareLinkLike, context: AccessContext) -> bool:
        return _link_active(link) and link.audience == AUDIENCE_WORLD

    def can_read_data(self, link: ShareLinkLike, context: AccessContext) -> bool:
        return self.can_discover(link, context)


class TrackerWorldPolicy(AccessPolicy):
    """Unauthenticated world token. Redacts account PII from payloads."""

    def can_discover(self, link: ShareLinkLike, context: AccessContext) -> bool:
        return _link_active(link) and link.audience == AUDIENCE_WORLD

    def can_read_data(self, link: ShareLinkLike, context: AccessContext) -> bool:
        return self.can_discover(link, context)


class TrackerInternalPolicy(AccessPolicy):
    """Authenticated deeplink. Owner, named grantee, or catalog-public visibility."""

    def can_discover(self, link: ShareLinkLike, context: AccessContext) -> bool:
        if not _link_active(link) or link.audience != AUDIENCE_AUTHENTICATED:
            return False
        if not _is_authenticated(context.user):
            return False
        if context.is_owner or context.is_grantee:
            return True
        return context.visibility == "public"

    def can_read_data(self, link: ShareLinkLike, context: AccessContext) -> bool:
        return self.can_discover(link, context)

    def redact(self, payload: dict[str, Any]) -> dict[str, Any]:
        return payload


class TrackerAuthenticatedPublicPolicy(AccessPolicy):
    """Catalog-public tracks/groups: any signed-in user, no world token required."""

    def can_discover(self, link: ShareLinkLike, context: AccessContext) -> bool:
        return _is_authenticated(context.user)

    def can_read_data(self, link: ShareLinkLike, context: AccessContext) -> bool:
        return self.can_discover(link, context)

    def redact(self, payload: dict[str, Any]) -> dict[str, Any]:
        return payload


def policy_for(link: ShareLinkLike) -> AccessPolicy:
    if link.domain == DOMAIN_MAP:
        return MapWorldPolicy()
    if link.audience == AUDIENCE_WORLD:
        return TrackerWorldPolicy()
    return TrackerInternalPolicy()
