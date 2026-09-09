"""Serve only dist/ and the declared icon for enabled extensions."""
import logging
from pathlib import Path

from django.conf import settings
from django.http import HttpRequest, HttpResponse
from django.views.static import serve

from geo_lib.utils.secure_path import is_path_under_base, secure_path
from website.settings import EXTENSIONS_DIR

logger = logging.getLogger("website.extensions.static_bundle")


class ExtensionStaticBundle:
    """dist_root + icon allowlist, enabled gate, cache policy."""

    def serve(self, request: HttpRequest, path: str, **kwargs) -> HttpResponse:
        from website.extensions.registry import get_extension_registry, kebab_name

        parts = path.split("/", 1)
        if not parts or not parts[0]:
            return HttpResponse(status=404)

        kebab = parts[0]
        folder_name = kebab.replace("-", "_")
        remainder = parts[1] if len(parts) > 1 else ""

        registry = get_extension_registry()
        loaded = registry.static_bundle(folder_name)
        if loaded is None:
            return HttpResponse(status=404)
        if kebab_name(loaded.name) != kebab:
            return HttpResponse(status=404)

        if not remainder.strip():
            return HttpResponse(status=404)
        remainder = secure_path(remainder)
        if remainder == "_":
            return HttpResponse(status=404)

        icon_name = None
        if loaded.manifest.icon and "/" not in loaded.manifest.icon and loaded.manifest.icon.endswith(
            (".svg", ".png", ".jpg", ".jpeg", ".webp")
        ):
            icon_name = loaded.manifest.icon

        allowed = False
        if remainder == icon_name:
            allowed = True
            disk_root = loaded.root
            serve_path = remainder
        elif remainder == "dist" or remainder.startswith("dist/"):
            allowed = True
            disk_root = loaded.root / "src" / "frontend"
            serve_path = remainder
        if not allowed:
            return HttpResponse(status=404)

        extensions_base = Path(EXTENSIONS_DIR)
        try:
            candidate = (disk_root / serve_path).resolve()
        except (OSError, RuntimeError):
            return HttpResponse(status=404)

        if not is_path_under_base(candidate, extensions_base):
            return HttpResponse(status=404)
        if not is_path_under_base(candidate, loaded.root):
            return HttpResponse(status=404)
        if remainder.startswith("dist/") or remainder == "dist":
            if not is_path_under_base(candidate, loaded.dist_root):
                return HttpResponse(status=404)

        logger.debug("Extension static serving: %s (root: %s)", serve_path, disk_root)
        kwargs["document_root"] = str(disk_root)
        response = serve(request, serve_path, **kwargs)
        if response.status_code == 200:
            if settings.DEBUG:
                response["Cache-Control"] = "no-cache, must-revalidate"
            else:
                response["Cache-Control"] = "public, max-age=31536000, immutable"
        return response
