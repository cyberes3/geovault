import importlib
import importlib.util
import logging
import sys
from pathlib import Path
from typing import List, Optional, Dict, Any

from website.config_loader import get_config_loader
from website.extensions.extension_base import ExtensionAppConfig

logger = logging.getLogger('website.extension_loader')


class ExtensionRegistry:
    """
    Registry for discovering and loading extensions.
    """

    def __init__(self, extensions_dir: Path):
        self.extensions_dir = extensions_dir
        self.loaded_extensions: Dict[str, Dict[str, Any]] = {}

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

        config_loader = get_config_loader()
        installed_apps_additions: List[str] = []
        loaded_names: List[str] = []

        # Track extension names to detect duplicates before loading
        extension_names: Dict[str, List[str]] = {}  # name -> list of folder names

        logger.info(f"Scanning for extensions in {self.extensions_dir}")

        # First pass: scan all manifests to detect duplicates
        for item in self.extensions_dir.iterdir():
            if item.is_dir():
                manifest_path = item / 'manifest.py'
                if manifest_path.exists():
                    try:
                        spec = importlib.util.spec_from_file_location("manifest", manifest_path)
                        if spec is None or spec.loader is None:
                            continue

                        manifest = importlib.util.module_from_spec(spec)
                        sys.modules[f"extensions.{item.name}.manifest"] = manifest
                        spec.loader.exec_module(manifest)

                        if hasattr(manifest, 'name'):
                            ext_name = manifest.name
                            if ext_name not in extension_names:
                                extension_names[ext_name] = []
                            extension_names[ext_name].append(item.name)
                    except Exception as e:
                        logger.warning(f"Could not read manifest for {item.name}: {e}")

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

        # Second pass: load extensions (now we know there are no duplicates)
        for item in self.extensions_dir.iterdir():
            if item.is_dir():
                manifest_path = item / 'manifest.py'
                if manifest_path.exists():
                    try:
                        app_config = self._load_extension(item, manifest_path, config_loader)
                        if app_config:
                            installed_apps_additions.append(app_config)
                            # Extract extension name for logging
                            loaded_names.append(item.name)
                    except Exception as e:
                        logger.error(f"Failed to load extension {item.name}: {e}", exc_info=True)

        if loaded_names:
            logger.info(f"Successfully loaded {len(loaded_names)} extensions: {', '.join(loaded_names)}")
        else:
            logger.info("No extensions found or enabled.")

        return installed_apps_additions

    def _load_extension(self, extension_path: Path, manifest_path: Path, config_loader: Any) -> Optional[str]:
        """
        Validates manifest and registers the extension.
        Returns the AppConfig string/class path if successful.
        """
        # Load manifest
        spec = importlib.util.spec_from_file_location("manifest", manifest_path)
        if spec is None or spec.loader is None:
            logger.error(f"Could not load manifest for {extension_path.name}")
            return None

        manifest = importlib.util.module_from_spec(spec)
        sys.modules[f"extensions.{extension_path.name}.manifest"] = manifest
        spec.loader.exec_module(manifest)

        # Validate required fields
        if not hasattr(manifest, 'name') or not hasattr(manifest, 'version'):
            logger.error(f"Extension at {extension_path} missing 'name' or 'version' in manifest.py")
            return None

        ext_name = manifest.name

        # Check if enabled in config
        # config key: extensions.<name>.enabled
        # Default to manifest.enabled_by_default (True if not specified)
        default_enabled = getattr(manifest, 'enabled_by_default', True)
        enabled = config_loader.get_bool(f'extensions.{ext_name}.enabled', default_enabled)

        if not enabled:
            logger.info(f"Extension '{ext_name}' is disabled in configuration.")
            return None

        logger.info(f"Loading extension: {ext_name} v{manifest.version}")

        # Determine the Django app path
        # Assumption: Logic lives in src/backend relative to extension root
        backend_path = extension_path / 'src' / 'backend'

        if not backend_path.exists():
            logger.warning(f"Extension {ext_name} has no 'src/backend' directory. Skipping Django app registration.")
            return None

        # Check for existing apps.py
        apps_py_path = backend_path / 'apps.py'
        module_name = f"{extension_path.name}.src.backend"
        # Use full module path with extensions. prefix to match import paths used in code
        full_module_name = f"extensions.{module_name}"

        # Determine the AppConfig to use
        if apps_py_path.exists():
            # When apps.py exists, we need to find the AppConfig class
            # Import the apps module and find the AppConfig class
            # Import using short name since extensions/ is in sys.path
            apps_module_name = f"{module_name}.apps"
            try:
                apps_module = importlib.import_module(apps_module_name)
                # Find the AppConfig class (look for classes ending in "Config" that inherit from AppConfig)
                from django.apps import AppConfig as DjangoAppConfig
                app_config_class = None
                for attr_name in dir(apps_module):
                    if attr_name.endswith('Config'):
                        attr = getattr(apps_module, attr_name)
                        if (isinstance(attr, type) and 
                            issubclass(attr, DjangoAppConfig) and 
                            attr is not DjangoAppConfig):
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
            app_config_path = self._create_dynamic_app_config(ext_name, module_name, full_module_name)

        # Extract frontend metadata
        frontend_entry = None
        frontend_css = None
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
                frontend_entry = f"/extensions/static/{kebab_name}/src/frontend/dist/{js_files[0].name}"
            else:
                assets_dir = dist_path / 'assets'
                assets_js = list(assets_dir.glob('index*.js')) if assets_dir.exists() else []
                if assets_js:
                    frontend_entry = f"/extensions/static/{kebab_name}/src/frontend/dist/assets/{assets_js[0].name}"

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
                frontend_css = f"/extensions/static/{kebab_name}/src/frontend/dist/{css_files[0].name}"
            else:
                assets_dir = dist_path / 'assets'
                assets_css = list(assets_dir.glob('*.css')) if assets_dir.exists() else []
                if assets_css:
                    frontend_css = f"/extensions/static/{kebab_name}/src/frontend/dist/assets/{assets_css[0].name}"

        # Check for urls.py
        urls_module = None
        if (backend_path / 'urls.py').exists():
            # Use full module path with extensions. prefix to match import paths
            urls_module = f"{full_module_name}.urls"

        # Extract icon from manifest (optional)
        # Icon can be: heroicon name (string), SVG path (string), or inline SVG (string)
        icon = getattr(manifest, 'icon', None)

        # Store extension metadata (internal use includes urls_module)
        self.loaded_extensions[ext_name] = {
            'name': ext_name,
            'version': manifest.version,
            'description': getattr(manifest, 'description', ''),
            'frontend_entry': frontend_entry,
            'frontend_css': frontend_css,
            'icon': icon,  # Optional icon field
            '_urls_module': urls_module  # Internal only, prefixed with underscore
        }

        return app_config_path

    def _create_dynamic_app_config(self, ext_name: str, module_name: str, full_module_name: str) -> str:
        """
        Creates a dynamic AppConfig class inheriting from ExtensionAppConfig.
        
        This ensures extensions get the ready() lifecycle and hook registration
        capabilities even if they don't define their own apps.py.
        
        The dynamic AppConfig:
        - Inherits from ExtensionAppConfig (provides ready() lifecycle)
        - Sets proper name, label, and verbose_name
        - Allows extensions to implement extension_ready() for initialization
        """
        try:
            # Ensure the module is imported (using short name since extensions/ is in sys.path)
            if module_name not in sys.modules:
                importlib.import_module(module_name)

            module = sys.modules[module_name]

            # Define the class name
            class_name = f"{ext_name.capitalize()}Config"

            # Create the class dynamically, inheriting from ExtensionAppConfig
            # Use full_module_name for AppConfig.name to match import paths
            app_config_attrs = {
                'name': full_module_name,
                'label': ext_name,
                'verbose_name': ext_name.replace('_', ' ').title(),
                'default_auto_field': 'django.db.models.BigAutoField'
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
        
        Returns:
            List of extension metadata dicts (excludes internal fields prefixed with _)
        """
        # Filter out internal fields (prefixed with underscore) from API response
        return [
            {k: v for k, v in ext.items() if not k.startswith('_')}
            for ext in self.loaded_extensions.values()
        ]

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


def get_extension_registry() -> ExtensionRegistry:
    """Returns the global extension registry instance."""
    global _registry
    if _registry is None:
        from website.settings import EXTENSIONS_DIR
        _registry = ExtensionRegistry(EXTENSIONS_DIR)
    return _registry


_registry: Optional[ExtensionRegistry] = None


def discover_extensions(extensions_dir: Path) -> List[str]:
    """
    Global helper to discover extensions.
    """
    global _registry
    if _registry is None:
        _registry = ExtensionRegistry(extensions_dir)
    return _registry.discover_extensions()
