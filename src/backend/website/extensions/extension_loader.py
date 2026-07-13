import hashlib
import importlib
import importlib.util
import logging
import os
import sys
from pathlib import Path
from typing import List, Optional, Dict, Any

from django.conf import settings

from website.config.loader import get_config
from website.extensions.extension_base import ExtensionAppConfig

logger = logging.getLogger('website.extension_loader')


def _static_url_with_version(file_path: Path, url_path: str) -> str:
    """
    Append a content-based ?v= query string to url_path for cache busting.
    When the file is rebuilt, the hash changes so browsers fetch the new file.
    """
    content = file_path.read_bytes()
    h = hashlib.sha256(content).hexdigest()[:12]
    return f"{url_path}?v={h}"


def _register_module_with_prefix(short_name: str, full_name: str, module: Any) -> None:
    """
    Register a module under both its short name and full name with extensions. prefix.
    
    Since extensions/ is in sys.path, modules import as 'extension_name.src.backend'.
    But AppConfig.name uses 'extensions.extension_name.src.backend'. This function
    makes the module importable under both names.
    
    IMPORTANT: Updates __module__ attributes to prevent Django from seeing duplicate models.
    """
    # Register module under full name
    sys.modules[full_name] = module
    
    # Update module's __name__ so future imports use the full path
    if hasattr(module, '__name__'):
        module.__name__ = full_name
    
    # Update __module__ for classes already in this module to prevent duplicate model registration
    if hasattr(module, '__dict__'):
        for attr_value in module.__dict__.values():
            if isinstance(attr_value, type) and hasattr(attr_value, '__module__'):
                if attr_value.__module__.startswith(short_name):
                    attr_value.__module__ = attr_value.__module__.replace(
                        short_name, full_name, 1
                    )
    
    # Create parent namespace modules if needed
    parts = full_name.split('.')
    for i in range(1, len(parts)):
        parent = '.'.join(parts[:i])
        if parent not in sys.modules:
            from types import ModuleType
            sys.modules[parent] = ModuleType(parent)


class ExtensionRegistry:
    """
    Registry for discovering and loading extensions.
    """

    def __init__(self, extensions_dir: Path, forced_enabled_extensions: Optional[set] = None):
        self.extensions_dir = extensions_dir
        self.loaded_extensions: Dict[str, Dict[str, Any]] = {}
        # Test-only escape hatch: names in this set are treated as enabled regardless
        # of config.yaml/manifest defaults. Only populated for the real app-loading
        # registry (see module-level discover_extensions()), never for ad hoc registries
        # that tests construct directly to exercise the enable/disable logic itself.
        self.forced_enabled_extensions = forced_enabled_extensions or set()

    def discover_extensions(self) -> List[str]:
        """
        Scans the extensions directory and returns a list of Django app configs
        or module paths to be added to INSTALLED_APPS.
        
        Raises:
            SystemExit: If duplicate extension names are detected
        """
        if not self.extensions_dir.exists():
            logger.warning(f"Extensions directory not found: {self.extensions_dir}")
            return []

        # Add extensions directory to sys.path so we can import them
        if str(self.extensions_dir) not in sys.path:
            sys.path.insert(0, str(self.extensions_dir))

        installed_apps_additions: List[str] = []

        logger.info(f"Scanning for extensions in {self.extensions_dir}")

        # Load each extension's manifest exactly once (iterating in sorted order for
        # deterministic discovery), then reuse the loaded module both to detect
        # duplicate extension names and to actually load the extension below.
        extension_paths: Dict[str, Path] = {}  # folder name -> extension directory
        manifests: Dict[str, Any] = {}  # folder name -> loaded manifest module
        extension_names: Dict[str, List[str]] = {}  # manifest name -> list of folder names

        for item in sorted(self.extensions_dir.iterdir()):
            if not item.is_dir():
                continue
            manifest_path = item / 'manifest.py'
            if not manifest_path.exists():
                continue
            try:
                manifest = self._load_manifest_module(item.name, manifest_path)
            except Exception as e:
                logger.warning(f"Could not read manifest for {item.name}: {e}")
                continue

            extension_paths[item.name] = item
            manifests[item.name] = manifest
            if hasattr(manifest, 'name'):
                extension_names.setdefault(manifest.name, []).append(item.name)

        # Check for duplicates
        duplicates = {name: folders for name, folders in extension_names.items() if len(folders) > 1}
        if duplicates:
            logger.error("=" * 60)
            logger.error("DUPLICATE EXTENSION NAMES DETECTED!")
            logger.error("=" * 60)
            logger.error("Multiple extensions have the same 'name' in their manifest.py:")
            for dup_name, folders in duplicates.items():
                logger.error(f"  - Extension name '{dup_name}' is used by multiple extensions")
                logger.error(f"    Found in folders: {', '.join(folders)}")
            logger.error("")
            logger.error("Each extension must have a unique 'name' in manifest.py.")
            logger.error("Please rename one of the conflicting extensions.")
            logger.error("=" * 60)
            sys.exit(1)

        # Load extensions (now we know there are no duplicates), reusing the manifests
        # already loaded above instead of re-executing manifest.py a second time.
        for folder_name, manifest in manifests.items():
            try:
                app_config = self._load_extension(extension_paths[folder_name], manifest)
                if app_config:
                    installed_apps_additions.append(app_config)
            except Exception as e:
                logger.error(f"Failed to load extension {folder_name}: {e}", exc_info=True)

        loaded_names = list(self.loaded_extensions.keys())
        if loaded_names:
            logger.info(f"Successfully loaded {len(loaded_names)} extensions: {', '.join(loaded_names)}")
        else:
            logger.info("No extensions found or enabled.")

        return installed_apps_additions

    def _load_manifest_module(self, folder_name: str, manifest_path: Path) -> Any:
        """
        Executes an extension's manifest.py and returns the resulting module.
        Raises if the spec/loader can't be created or the manifest itself raises.
        """
        spec = importlib.util.spec_from_file_location("manifest", manifest_path)
        if spec is None or spec.loader is None:
            raise ImportError(f"Could not create module spec for {manifest_path}")

        manifest = importlib.util.module_from_spec(spec)
        sys.modules[f"extensions.{folder_name}.manifest"] = manifest
        spec.loader.exec_module(manifest)
        return manifest

    def _load_extension(self, extension_path: Path, manifest: Any) -> Optional[str]:
        """
        Validates the (already-loaded) manifest and registers the extension.
        Returns the AppConfig string/class path if the extension provides a Django
        app, or None if it's disabled or frontend-only (no 'src/backend' directory).
        """
        # Validate required fields
        if not hasattr(manifest, 'name') or not hasattr(manifest, 'version'):
            logger.error(f"Extension at {extension_path} missing 'name' or 'version' in manifest.py")
            return None

        ext_name = manifest.name

        # Check if enabled in config
        # config key: extensions.<name>.enabled
        # Default to manifest.enabled_by_default (True if not specified)
        default_enabled = getattr(manifest, 'enabled_by_default', True)
        # Intentional exception to the settings-only config rule: extension discovery runs
        # during Django app loading (INSTALLED_APPS computation in website.settings), before
        # django.conf.settings exists, so this must read the validated config object directly.
        enabled = bool(get_config().extension_settings(ext_name).get('enabled', default_enabled))

        if ext_name in self.forced_enabled_extensions:
            enabled = True

        if not enabled:
            logger.info(f"Extension '{ext_name}' is disabled in configuration.")
            return None

        logger.info(f"Loading extension: {ext_name} v{manifest.version}")

        # Determine the Django app path, if any.
        # Assumption: Logic lives in src/backend relative to extension root.
        # Extensions without a 'src/backend' directory are frontend-only: they still
        # get their frontend metadata registered below, but contribute no Django app.
        backend_path = extension_path / 'src' / 'backend'
        has_backend = backend_path.exists()

        module_name = f"{extension_path.name}.src.backend"
        # Use full module path with extensions. prefix to match import paths used in code
        full_module_name = f"extensions.{module_name}"

        if not has_backend:
            logger.info(f"Extension '{ext_name}' has no 'src/backend' directory; registering as frontend-only.")
            app_config_path = None
        else:
            # Check for existing apps.py
            apps_py_path = backend_path / 'apps.py'

            # Determine the AppConfig to use
            if apps_py_path.exists():
                # When apps.py exists, we need to find the AppConfig class
                # Import the apps module and find the AppConfig class
                # Import using short name since extensions/ is in sys.path
                apps_module_name = f"{module_name}.apps"
                try:
                    apps_module = importlib.import_module(apps_module_name)

                    # Register modules with extensions. prefix so they're importable
                    # using the full path that matches AppConfig.name
                    if module_name not in sys.modules:
                        importlib.import_module(module_name)
                    if module_name in sys.modules:
                        _register_module_with_prefix(module_name, full_module_name, sys.modules[module_name])

                    full_apps_module_name = f"{full_module_name}.apps"
                    _register_module_with_prefix(apps_module_name, full_apps_module_name, apps_module)

                    # Find the AppConfig class (look for classes ending in "Config" that inherit from AppConfig)
                    from django.apps import AppConfig as DjangoAppConfig
                    from website.extensions.extension_base import ExtensionAppConfig
                    app_config_class = None
                    for attr_name in dir(apps_module):
                        if attr_name.endswith('Config'):
                            attr = getattr(apps_module, attr_name)
                            if (isinstance(attr, type) and
                                issubclass(attr, DjangoAppConfig) and
                                attr is not DjangoAppConfig and
                                attr is not ExtensionAppConfig and
                                (attr.__module__ == apps_module_name or
                                 attr.__module__ == full_apps_module_name)):
                                app_config_class = attr_name
                                break

                    if app_config_class:
                        # Return full path with extensions. prefix to match AppConfig.name
                        app_config_path = f"{full_module_name}.apps.{app_config_class}"
                    else:
                        # Fallback: just use module name (Django will try to auto-discover)
                        logger.warning(f"Could not find AppConfig class in {apps_module_name}, using module name")
                        app_config_path = full_module_name
                except Exception as e:
                    logger.warning(f"Failed to import {apps_module_name}: {e}, using module name")
                    app_config_path = full_module_name
            else:
                app_config_path = self._create_dynamic_app_config(ext_name, module_name, full_module_name, backend_path)

        # Extract frontend metadata
        frontend_entry = None
        frontend_css = None
        frontend_entry_path: Optional[Path] = None
        frontend_css_path: Optional[Path] = None
        dist_path = extension_path / 'src' / 'frontend' / 'dist'

        if dist_path.exists():
            # Use kebab-case for the URL path
            kebab_name = extension_path.name.replace('_', '-')

            # 1. Discover JS entry point
            js_files = list(dist_path.glob('index*.*js'))
            if js_files:
                def js_sort_key(f):
                    name = f.name
                    if name == 'index.js': return 0
                    if '.umd.' in name: return 1
                    if '.iife.' in name: return 2
                    return 3

                js_files.sort(key=js_sort_key)
                frontend_entry_path = dist_path / js_files[0].name
                frontend_entry = f"/extensions/static/{kebab_name}/src/frontend/dist/{js_files[0].name}"
                frontend_entry = _static_url_with_version(frontend_entry_path, frontend_entry)
            else:
                assets_dir = dist_path / 'assets'
                assets_js = list(assets_dir.glob('index*.js')) if assets_dir.exists() else []
                if assets_js:
                    frontend_entry_path = assets_dir / assets_js[0].name
                    frontend_entry = f"/extensions/static/{kebab_name}/src/frontend/dist/assets/{assets_js[0].name}"
                    frontend_entry = _static_url_with_version(frontend_entry_path, frontend_entry)

            # 2. Discover CSS entry point
            css_files = list(dist_path.glob('*.css'))
            if css_files:
                # Prefer index.css, then style.css, then anything else
                def css_sort_key(f):
                    name = f.name
                    if name == 'index.css': return 0
                    if name == 'style.css': return 1
                    return 2

                css_files.sort(key=css_sort_key)
                frontend_css_path = dist_path / css_files[0].name
                frontend_css = f"/extensions/static/{kebab_name}/src/frontend/dist/{css_files[0].name}"
                frontend_css = _static_url_with_version(frontend_css_path, frontend_css)
            else:
                assets_dir = dist_path / 'assets'
                assets_css = list(assets_dir.glob('*.css')) if assets_dir.exists() else []
                if assets_css:
                    frontend_css_path = assets_dir / assets_css[0].name
                    frontend_css = f"/extensions/static/{kebab_name}/src/frontend/dist/assets/{assets_css[0].name}"
                    frontend_css = _static_url_with_version(frontend_css_path, frontend_css)

        # Check for urls.py (frontend-only extensions have no backend, so no routes)
        urls_module = None
        if has_backend and (backend_path / 'urls.py').exists():
            # Use full module path with extensions. prefix to match import paths
            urls_module = f"{full_module_name}.urls"

        # Extract icon from manifest (optional)
        # Icon can be: heroicon name (string), SVG path (string), or inline SVG (string)
        icon = getattr(manifest, 'icon', None)
        # If True, frontend uses full-height map layout for this extension's routes
        map_route = getattr(manifest, 'map_route', False)
        # If True, frontend treats /extensions/<kebab-name>/share as a public (no-auth) share route
        public_share_route = getattr(manifest, 'public_share_route', False)

        # Store extension metadata (internal use includes urls_module and file paths for fresh ?v= in API)
        self.loaded_extensions[ext_name] = {
            'name': ext_name,
            'version': manifest.version,
            'frontend_entry': frontend_entry,
            'frontend_css': frontend_css,
            'icon': icon,  # Optional icon field
            'map_route': map_route,
            'public_share_route': public_share_route,
            '_urls_module': urls_module,  # Internal only, prefixed with underscore
            '_frontend_entry_path': frontend_entry_path,
            '_frontend_css_path': frontend_css_path,
        }

        return app_config_path

    def _create_dynamic_app_config(self, ext_name: str, module_name: str, full_module_name: str, backend_path: Path) -> str:
        """
        Creates a dynamic AppConfig class inheriting from ExtensionAppConfig.
        
        This ensures extensions get the ready() lifecycle and hook registration
        capabilities even if they don't define their own apps.py.
        
        The dynamic AppConfig:
        - Inherits from ExtensionAppConfig (provides ready() lifecycle)
        - Sets proper name, label, verbose_name, and path
        - Allows extensions to implement extension_ready() for initialization
        """
        try:
            # Ensure the module is imported (using short name since extensions/ is in sys.path)
            if module_name not in sys.modules:
                importlib.import_module(module_name)

            module = sys.modules[module_name]
            
            # Register module with extensions. prefix so it's importable using the full path
            # that matches AppConfig.name
            _register_module_with_prefix(module_name, full_module_name, module)

            # Define the class name
            class_name = f"{ext_name.capitalize()}Config"

            # Create the class dynamically, inheriting from ExtensionAppConfig
            # Use full_module_name for AppConfig.name to match import paths
            # Set path to the backend directory so Django knows the filesystem location
            app_config_attrs = {
                'name': full_module_name,
                'label': ext_name,
                'verbose_name': ext_name.replace('_', ' ').title(),
                'default_auto_field': 'django.db.models.BigAutoField',
                'path': str(backend_path.resolve())
            }

            # Inherit from ExtensionAppConfig instead of AppConfig
            dynamic_app_config = type(class_name, (ExtensionAppConfig,), app_config_attrs)

            # Attach it to the module
            setattr(module, class_name, dynamic_app_config)

            # Return full path with extensions. prefix to match AppConfig.name
            full_class_path = f"{full_module_name}.{class_name}"
            logger.debug(f"Created dynamic AppConfig (inheriting from ExtensionAppConfig): {full_class_path}")

            return full_class_path

        except Exception as e:
            logger.error(f"Failed to create dynamic AppConfig for {ext_name}: {e}")
            return module_name

    def get_loaded_extensions(self) -> List[Dict[str, Any]]:
        """
        Returns metadata for all loaded extensions.
        Used by the API to expose extension info to the frontend.
        Only includes frontend-relevant fields.
        In DEBUG, recomputes frontend_entry and frontend_css ?v= hash from current
        file so rebuilt extension assets get a new URL without restarting Django.
        In production, returns the startup URLs (no disk I/O per request).
        
        Returns:
            List of extension metadata dicts (excludes internal fields prefixed with _)
        """
        result = []
        for ext in self.loaded_extensions.values():
            out = {k: v for k, v in ext.items() if not k.startswith('_')}
            if settings.DEBUG:
                # Recompute versioned URLs from current file so rebuilds update the URL
                entry_path = ext.get('_frontend_entry_path')
                if entry_path is not None and isinstance(entry_path, Path) and entry_path.exists():
                    base = (ext.get('frontend_entry') or '').split('?')[0]
                    if base:
                        out['frontend_entry'] = _static_url_with_version(entry_path, base)
                css_path = ext.get('_frontend_css_path')
                if css_path is not None and isinstance(css_path, Path) and css_path.exists():
                    base = (ext.get('frontend_css') or '').split('?')[0]
                    if base:
                        out['frontend_css'] = _static_url_with_version(css_path, base)
            result.append(out)
        return result

    def get_extension_urls(self) -> List[Any]:
        """
        Generates Django URL patterns for all extensions that provide a urls.py.
        Maps underscores in extension names to hyphens for the URL prefix.
        """
        from django.urls import path, include
        patterns = []
        for ext_name, meta in self.loaded_extensions.items():
            if meta.get('_urls_module'):
                url_prefix = ext_name.replace('_', '-')
                patterns.append(path(f"extensions/{url_prefix}/", include(meta['_urls_module'])))
        return patterns


def _forced_enabled_extensions_from_env() -> set:
    """
    Test-only escape hatch: GEOVAULT_FORCE_ENABLED_EXTENSIONS is a comma-separated
    list of extension names that should be treated as enabled regardless of
    config.yaml/manifest defaults (e.g. the demo example_extension, which ships
    disabled by default but has a real test suite exercising its live endpoints).
    Only consulted for the singleton registry backing real Django app loading.
    """
    return {name.strip() for name in os.environ.get('GEOVAULT_FORCE_ENABLED_EXTENSIONS', '').split(',') if name.strip()}


def get_extension_registry() -> ExtensionRegistry:
    """Returns the global extension registry instance."""
    global _registry
    if _registry is None:
        from website.settings import EXTENSIONS_DIR
        _registry = ExtensionRegistry(EXTENSIONS_DIR, forced_enabled_extensions=_forced_enabled_extensions_from_env())
    return _registry


_registry: Optional[ExtensionRegistry] = None


def discover_extensions(extensions_dir: Path) -> List[str]:
    """
    Global helper to discover extensions.
    """
    global _registry
    if _registry is None:
        _registry = ExtensionRegistry(extensions_dir, forced_enabled_extensions=_forced_enabled_extensions_from_env())
    return _registry.discover_extensions()
