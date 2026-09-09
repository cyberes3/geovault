"""Mount extension Django URLConfs under /api/extensions/<kebab>/ with a namespace."""
from enum import Enum
from typing import TYPE_CHECKING, Any, Iterable

from django.urls import include, path

if TYPE_CHECKING:
    from website.extensions.registry import LoadedExtension


class AuthPolicy(str, Enum):
    """Default auth for extension HTTP. Views may opt into public/token routes themselves."""

    SESSION_CSRF = "session_csrf"
    PUBLIC = "public"
    TOKEN = "token"


class ExtensionHttpMount:
    """
    kebab prefix, namespace=name, default AuthPolicy.SESSION_CSRF.

    Session views must not stack @csrf_exempt. Ingress/Hauk/public GET declare
    their own policy on the view.
    """

    default_auth = AuthPolicy.SESSION_CSRF

    def url_patterns(self, loaded: Iterable["LoadedExtension"]) -> list[Any]:
        patterns = []
        for ext in loaded:
            if not ext.urls_module:
                continue
            url_prefix = ext.manifest.kebab_name
            patterns.append(
                path(
                    f"extensions/{url_prefix}/",
                    include((ext.urls_module, ext.name), namespace=ext.name),
                )
            )
        return patterns
