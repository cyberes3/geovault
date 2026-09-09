"""
Discover first-party extensions once at settings bootstrap.

One Python FQN: extensions.{name}.src.backend…  Folder name must equal manifest.name.
"""
import hashlib
import importlib
import importlib.util
import logging
import os
import sys
from types import ModuleType
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

from django.apps import AppConfig as DjangoAppConfig

from website.config.loader import get_config
from website.extensions.extension_base import ExtensionAppConfig
from website.extensions.http_mount import ExtensionHttpMount
from website.extensions.manifest import ExtensionManifest, discover_manifest_path, load_manifest

logger = logging.getLogger("website.extensions.registry")


def _static_url_with_version(file_path: Path, url_path: str) -> str:
    content = file_path.read_bytes()
    digest = hashlib.sha256(content).hexdigest()[:12]
    return f"{url_path}?v={digest}"


def kebab_name(name: str) -> str:
    return name.replace("_", "-")


@dataclass
class LoadedExtension:
    """Manifest plus resolved disk/import paths for one enabled extension."""

    manifest: ExtensionManifest
    folder_name: str
    root: Path
    django_app: Optional[str]
    frontend_entry: Optional[str]
    frontend_css: Optional[str]
    frontend_entry_path: Optional[Path]
    frontend_css_path: Optional[Path]
    urls_module: Optional[str]
    dist_root: Path = field(init=False)

    def __post_init__(self) -> None:
        self.dist_root = self.root / "src" / "frontend" / "dist"

    @property
    def name(self) -> str:
        return self.manifest.name

    @property
    def version(self) -> str:
        return self.manifest.version

    @property
    def icon(self) -> Optional[str]:
        return self.manifest.icon

    @property
    def map_route(self) -> bool:
        return self.manifest.map_route

    @property
    def public_share_route(self) -> bool:
        return self.manifest.public_share_route

    def to_api_dict(self, *, refresh_static_hash: bool = False) -> dict[str, Any]:
        frontend_entry = self.frontend_entry
        frontend_css = self.frontend_css
        if refresh_static_hash:
            if self.frontend_entry_path is not None and self.frontend_entry_path.exists() and frontend_entry:
                frontend_entry = _static_url_with_version(
                    self.frontend_entry_path, frontend_entry.split("?")[0]
                )
            if self.frontend_css_path is not None and self.frontend_css_path.exists() and frontend_css:
                frontend_css = _static_url_with_version(
                    self.frontend_css_path, frontend_css.split("?")[0]
                )
        return {
            "name": self.manifest.name,
            "version": self.manifest.version,
            "frontend_entry": frontend_entry,
            "frontend_css": frontend_css,
            "icon": self.manifest.icon,
            "map_route": self.manifest.map_route,
            "public_share_route": self.manifest.public_share_route,
            "description": self.manifest.description,
            "umd_global": self.manifest.umd_global,
            "requires_map_engine": self.manifest.requires_map_engine.value,
            "dashboard_widget": self.manifest.dashboard_widget,
        }

    def __getitem__(self, key: str) -> Any:
        if key == "_urls_module":
            return self.urls_module
        if key == "_frontend_entry_path":
            return self.frontend_entry_path
        if key == "_frontend_css_path":
            return self.frontend_css_path
        public = self.to_api_dict()
        if key in public:
            return public[key]
        raise KeyError(key)

    def __contains__(self, key: object) -> bool:
        if key in {"_urls_module", "_frontend_entry_path", "_frontend_css_path"}:
            return True
        return isinstance(key, str) and key in self.to_api_dict()


class ExtensionRegistry:
    """Discover and load extensions from EXTENSIONS_DIR."""

    def __init__(self, extensions_dir: Path, forced_enabled_extensions: Optional[set[str]] = None):
        self.extensions_dir = extensions_dir
        self.loaded_extensions: dict[str, LoadedExtension] = {}
        self.discovered_manifests: dict[str, ExtensionManifest] = {}
        self.forced_enabled_extensions = forced_enabled_extensions or set()
        self._http_mount = ExtensionHttpMount()

    def discover_extensions(self) -> list[str]:
        """
        Scan extensions_dir and return Django AppConfig paths for INSTALLED_APPS.

        Raises SystemExit on duplicate names or folder/name mismatch after a valid parse.
        """
        if not self.extensions_dir.exists():
            logger.warning("Extensions directory not found: %s", self.extensions_dir)
            return []

        installed_apps_additions: list[str] = []
        logger.info("Scanning for extensions in %s", self.extensions_dir)

        extension_paths: dict[str, Path] = {}
        manifests: dict[str, ExtensionManifest] = {}
        names_to_folders: dict[str, list[str]] = {}

        for item in sorted(self.extensions_dir.iterdir()):
            if not item.is_dir():
                continue
            manifest_path = discover_manifest_path(item)
            if manifest_path is None:
                continue
            try:
                manifest = load_manifest(manifest_path)
            except Exception as exc:
                logger.warning("Could not read manifest for %s: %s", item.name, exc)
                continue

            if manifest.name != item.name:
                logger.error("=" * 60)
                logger.error("EXTENSION FOLDER / MANIFEST NAME MISMATCH")
                logger.error("=" * 60)
                logger.error(
                    "Folder '%s' must equal manifest.name '%s'.",
                    item.name,
                    manifest.name,
                )
                sys.exit(1)

            extension_paths[item.name] = item
            manifests[item.name] = manifest
            names_to_folders.setdefault(manifest.name, []).append(item.name)

        self.discovered_manifests = {manifest.name: manifest for manifest in manifests.values()}

        duplicates = {
            name: folders for name, folders in names_to_folders.items() if len(folders) > 1
        }
        if duplicates:
            logger.error("=" * 60)
            logger.error("DUPLICATE EXTENSION NAMES DETECTED!")
            logger.error("=" * 60)
            for dup_name, folders in duplicates.items():
                logger.error("  - '%s' is used by folders: %s", dup_name, ", ".join(folders))
            sys.exit(1)

        for folder_name, manifest in manifests.items():
            try:
                app_config = self._load_extension(extension_paths[folder_name], manifest)
                if app_config:
                    installed_apps_additions.append(app_config)
            except Exception as exc:
                logger.error("Failed to load extension %s: %s", folder_name, exc, exc_info=True)

        loaded_names = list(self.loaded_extensions.keys())
        if loaded_names:
            logger.info(
                "Successfully loaded %s extensions: %s",
                len(loaded_names),
                ", ".join(loaded_names),
            )
        else:
            logger.info("No extensions found or enabled.")

        return installed_apps_additions

    def _is_enabled(self, manifest: ExtensionManifest) -> bool:
        enabled = bool(
            get_config().extension_settings(manifest.name).get("enabled", manifest.enabled_by_default)
        )
        if manifest.name in self.forced_enabled_extensions:
            return True
        return enabled

    def _load_extension(self, extension_path: Path, manifest: ExtensionManifest) -> Optional[str]:
        if not self._is_enabled(manifest):
            logger.info("Extension '%s' is disabled in configuration.", manifest.name)
            return None

        logger.info("Loading extension: %s v%s", manifest.name, manifest.version)

        backend_path = extension_path / "src" / "backend"
        has_backend = backend_path.exists()
        full_module_name = f"extensions.{extension_path.name}.src.backend"

        if not has_backend:
            logger.info(
                "Extension '%s' has no 'src/backend' directory; registering as frontend-only.",
                manifest.name,
            )
            app_config_path = None
        else:
            self._bind_backend_package(extension_path, full_module_name)
            apps_py_path = backend_path / "apps.py"
            if apps_py_path.exists():
                app_config_path = self._resolve_app_config(full_module_name)
            else:
                app_config_path = self._create_dynamic_app_config(
                    manifest.name, full_module_name, backend_path
                )

        frontend_entry, frontend_css, frontend_entry_path, frontend_css_path = self._discover_frontend(
            extension_path
        )

        urls_module = None
        if has_backend and (backend_path / "urls.py").exists():
            urls_module = f"{full_module_name}.urls"

        self.loaded_extensions[manifest.name] = LoadedExtension(
            manifest=manifest,
            folder_name=extension_path.name,
            root=extension_path,
            django_app=app_config_path,
            frontend_entry=frontend_entry,
            frontend_css=frontend_css,
            frontend_entry_path=frontend_entry_path,
            frontend_css_path=frontend_css_path,
            urls_module=urls_module,
        )
        return app_config_path

    def _bind_backend_package(self, extension_path: Path, full_module_name: str) -> None:
        """
        Register extensions.{name}.src.backend under its FQN from disk.

        Real first-party extensions are already importable. Temp-dir fixtures used
        by tests are not on the package path; bind those explicitly instead of
        injecting a directory onto sys.path.
        """
        try:
            importlib.import_module(full_module_name)
            return
        except ImportError:
            pass

        folder_name = extension_path.name
        name_pkg = f"extensions.{folder_name}"
        src_pkg = f"{name_pkg}.src"
        backend_path = extension_path / "src" / "backend"

        if name_pkg not in sys.modules:
            package = ModuleType(name_pkg)
            package.__path__ = [str(extension_path)]
            package.__package__ = name_pkg
            sys.modules[name_pkg] = package
        if src_pkg not in sys.modules:
            package = ModuleType(src_pkg)
            package.__path__ = [str(extension_path / "src")]
            package.__package__ = src_pkg
            sys.modules[src_pkg] = package

        init_py = backend_path / "__init__.py"
        if full_module_name not in sys.modules:
            if init_py.exists():
                spec = importlib.util.spec_from_file_location(
                    full_module_name,
                    init_py,
                    submodule_search_locations=[str(backend_path)],
                )
                if spec is None or spec.loader is None:
                    return
                module = importlib.util.module_from_spec(spec)
                module.__package__ = full_module_name
                sys.modules[full_module_name] = module
                spec.loader.exec_module(module)
            else:
                package = ModuleType(full_module_name)
                package.__path__ = [str(backend_path)]
                package.__package__ = full_module_name
                sys.modules[full_module_name] = package

        urls_path = backend_path / "urls.py"
        urls_name = f"{full_module_name}.urls"
        if urls_path.exists() and urls_name not in sys.modules:
            spec = importlib.util.spec_from_file_location(urls_name, urls_path)
            if spec is not None and spec.loader is not None:
                module = importlib.util.module_from_spec(spec)
                module.__package__ = full_module_name
                sys.modules[urls_name] = module
                spec.loader.exec_module(module)

        apps_path = backend_path / "apps.py"
        apps_name = f"{full_module_name}.apps"
        if apps_path.exists() and apps_name not in sys.modules:
            spec = importlib.util.spec_from_file_location(apps_name, apps_path)
            if spec is not None and spec.loader is not None:
                module = importlib.util.module_from_spec(spec)
                module.__package__ = full_module_name
                sys.modules[apps_name] = module
                spec.loader.exec_module(module)

    def _resolve_app_config(self, full_module_name: str) -> str:
        apps_module_name = f"{full_module_name}.apps"
        try:
            apps_module = importlib.import_module(apps_module_name)
            app_config_class = None
            for attr_name in dir(apps_module):
                if not attr_name.endswith("Config"):
                    continue
                attr = getattr(apps_module, attr_name)
                if (
                    isinstance(attr, type)
                    and issubclass(attr, DjangoAppConfig)
                    and attr is not DjangoAppConfig
                    and attr is not ExtensionAppConfig
                    and attr.__module__ in {apps_module_name}
                ):
                    app_config_class = attr_name
                    break
            if app_config_class:
                return f"{apps_module_name}.{app_config_class}"
            logger.warning("Could not find AppConfig class in %s, using module name", apps_module_name)
            return full_module_name
        except Exception as exc:
            logger.warning("Failed to import %s: %s, using module name", apps_module_name, exc)
            return full_module_name

    def _create_dynamic_app_config(
        self, ext_name: str, full_module_name: str, backend_path: Path
    ) -> str:
        try:
            module = importlib.import_module(full_module_name)
            class_name = f"{ext_name.capitalize()}Config"
            app_config_attrs = {
                "name": full_module_name,
                "label": ext_name,
                "verbose_name": ext_name.replace("_", " ").title(),
                "default_auto_field": "django.db.models.BigAutoField",
                "path": str(backend_path.resolve()),
            }
            dynamic_app_config = type(class_name, (ExtensionAppConfig,), app_config_attrs)
            setattr(module, class_name, dynamic_app_config)
            return f"{full_module_name}.{class_name}"
        except Exception as exc:
            logger.error("Failed to create dynamic AppConfig for %s: %s", ext_name, exc)
            return full_module_name

    def _discover_frontend(
        self, extension_path: Path
    ) -> tuple[Optional[str], Optional[str], Optional[Path], Optional[Path]]:
        dist_path = extension_path / "src" / "frontend" / "dist"
        if not dist_path.exists():
            return None, None, None, None

        kebab = kebab_name(extension_path.name)
        static_prefix = f"/extensions/static/{kebab}/dist"

        frontend_entry = None
        frontend_entry_path = None
        js_files = list(dist_path.glob("index*.*js"))
        if js_files:
            def js_sort_key(file_path: Path) -> int:
                name = file_path.name
                if name == "index.js":
                    return 0
                if ".umd." in name:
                    return 1
                if ".iife." in name:
                    return 2
                return 3

            js_files.sort(key=js_sort_key)
            frontend_entry_path = dist_path / js_files[0].name
            frontend_entry = _static_url_with_version(
                frontend_entry_path, f"{static_prefix}/{js_files[0].name}"
            )
        else:
            assets_dir = dist_path / "assets"
            assets_js = list(assets_dir.glob("index*.js")) if assets_dir.exists() else []
            if assets_js:
                frontend_entry_path = assets_dir / assets_js[0].name
                frontend_entry = _static_url_with_version(
                    frontend_entry_path, f"{static_prefix}/assets/{assets_js[0].name}"
                )

        frontend_css = None
        frontend_css_path = None
        css_files = list(dist_path.glob("*.css"))
        if css_files:
            def css_sort_key(file_path: Path) -> int:
                name = file_path.name
                if name == "index.css":
                    return 0
                if name == "style.css":
                    return 1
                return 2

            css_files.sort(key=css_sort_key)
            frontend_css_path = dist_path / css_files[0].name
            frontend_css = _static_url_with_version(
                frontend_css_path, f"{static_prefix}/{css_files[0].name}"
            )
        else:
            assets_dir = dist_path / "assets"
            assets_css = list(assets_dir.glob("*.css")) if assets_dir.exists() else []
            if assets_css:
                frontend_css_path = assets_dir / assets_css[0].name
                frontend_css = _static_url_with_version(
                    frontend_css_path, f"{static_prefix}/assets/{assets_css[0].name}"
                )

        return frontend_entry, frontend_css, frontend_entry_path, frontend_css_path

    def list_enabled(self) -> list[LoadedExtension]:
        return list(self.loaded_extensions.values())

    def get_loaded_extensions(self) -> list[dict[str, Any]]:
        from django.conf import settings

        refresh = bool(getattr(settings, "DEBUG", False))
        return [ext.to_api_dict(refresh_static_hash=refresh) for ext in self.loaded_extensions.values()]

    def url_patterns(self) -> list[Any]:
        return self._http_mount.url_patterns(self.list_enabled())

    def get_extension_urls(self) -> list[Any]:
        return self.url_patterns()

    def static_bundle(self, name: str) -> Optional[LoadedExtension]:
        return self.loaded_extensions.get(name)

    def baked_route_prefixes(self) -> dict[str, list[str]]:
        """Public-share and map prefixes from every discovered manifest (enabled or not)."""
        map_prefixes: list[str] = []
        share_prefixes: list[str] = []
        for manifest in self.discovered_manifests.values():
            prefix = f"/extensions/{manifest.kebab_name}"
            if manifest.map_route:
                map_prefixes.append(prefix)
            if manifest.public_share_route:
                share_prefixes.append(f"{prefix}/share")
        return {
            "mapRoutePrefixes": map_prefixes,
            "publicShareRoutePrefixes": share_prefixes,
        }


def _forced_enabled_extensions_from_env() -> set[str]:
    """
    Test-only escape hatch: GEOVAULT_FORCE_ENABLED_EXTENSIONS is a comma-separated
    list of extension names treated as enabled regardless of config.yaml/manifest
    defaults (example_extension ships disabled by default).
    """
    return {
        name.strip()
        for name in os.environ.get("GEOVAULT_FORCE_ENABLED_EXTENSIONS", "").split(",")
        if name.strip()
    }


_registry: Optional[ExtensionRegistry] = None


def get_extension_registry() -> ExtensionRegistry:
    global _registry
    if _registry is None:
        from website.settings import EXTENSIONS_DIR

        _registry = ExtensionRegistry(
            EXTENSIONS_DIR, forced_enabled_extensions=_forced_enabled_extensions_from_env()
        )
    return _registry


def discover_extensions(extensions_dir: Path) -> list[str]:
    global _registry
    if _registry is None:
        _registry = ExtensionRegistry(
            extensions_dir, forced_enabled_extensions=_forced_enabled_extensions_from_env()
        )
    return _registry.discover_extensions()
