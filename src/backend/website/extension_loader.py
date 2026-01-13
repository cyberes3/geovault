import importlib
import importlib.util
import logging
import sys
from pathlib import Path
from typing import List, Optional, Dict, Any, Type

from django.apps import AppConfig
from django.conf import settings

from website.config_loader import get_config_loader

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

        logger.info(f"Scanning for extensions in {self.extensions_dir}")

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
        
        # Determine the AppConfig to use
        app_config_path = module_name
        if apps_py_path.exists():
             app_config_path = module_name
        else:
            app_config_path = self._create_dynamic_app_config(ext_name, module_name)

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
            urls_module = f"{module_name}.urls"

        # Register in metadata registry for API
        self.loaded_extensions[ext_name] = {
            'name': ext_name,
            'version': manifest.version,
            'description': getattr(manifest, 'description', ''),
            'frontend_entry': frontend_entry,
            'frontend_css': frontend_css,
            'urls_module': urls_module
        }

        return app_config_path

    def _create_dynamic_app_config(self, ext_name: str, module_name: str) -> str:
        """
        Creates a dynamic AppConfig class to ensure the label is set correctly.
        """
        try:
            # Ensure the module is imported
            if module_name not in sys.modules:
                importlib.import_module(module_name)
            
            module = sys.modules[module_name]
            
            # Define the class name
            class_name = f"{ext_name.capitalize()}Config"
            
            # Create the class dynamically
            app_config_attrs = {
                'name': module_name,
                'label': ext_name,
                'verbose_name': ext_name.replace('_', ' ').title()
            }
            
            dynamic_app_config = type(class_name, (AppConfig,), app_config_attrs)
            
            # Attach it to the module
            setattr(module, class_name, dynamic_app_config)
            
            full_class_path = f"{module_name}.{class_name}"
            logger.debug(f"Created dynamic AppConfig: {full_class_path}")
            
            return full_class_path
            
        except Exception as e:
            logger.error(f"Failed to create dynamic AppConfig for {ext_name}: {e}")
            return module_name

    def get_active_extensions(self) -> List[Dict[str, Any]]:
        """Returns metadata for all loaded and enabled extensions."""
        return list(self.loaded_extensions.values())

    def get_extension_urls(self) -> List[Any]:
        """
        Generates Django URL patterns for all extensions that provide a urls.py.
        Maps underscores in extension names to hyphens for the URL prefix.
        """
        from django.urls import path, include
        patterns = []
        for ext_name, meta in self.loaded_extensions.items():
            if meta.get('urls_module'):
                # Map underscores to hyphens for URL prefix
                url_prefix = ext_name.replace('_', '-')
                patterns.append(path(f"extensions/{url_prefix}/", include(meta['urls_module'])))
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
