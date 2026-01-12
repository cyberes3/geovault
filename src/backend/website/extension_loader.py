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
        # Default to True if not specified
        enabled = config_loader.get_bool(f'extensions.{ext_name}.enabled', True)
        
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
        if apps_py_path.exists():
            # If user provided apps.py, check if we need to verify the label
            # We trust the user's apps.py but we should warn if label doesn't match
            # For simplicity, we just use the module path which Django will inspect
            # BUT, to enforce label, we might need to import it.
            # Let's try to import it to allow custom AppConfigs.
             return f"{extension_path.name}.src.backend"
        else:
            # Dynamic AppConfig generation
            # This ensures the app label matches the extension name
            # resulting in tables like 'extensionname_modelname'
            return self._create_dynamic_app_config(ext_name, module_name)

    def _create_dynamic_app_config(self, ext_name: str, module_name: str) -> str:
        """
        Creates a dynamic AppConfig class to ensure the label is set correctly.
        Returns the fully qualified name of the class factory or similar?
        
        Django INSTALLED_APPS expects a dotted path to an AppConfig class OR a package.
        If we want to generate a class, we need to attach it to a module.
        """
        
        # To make this robust, we can't easily pass a class object via INSTALLED_APPS list 
        # in settings.py if it's not importable. 
        # So we need to create the class and inject it into the extension's module space.
        
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
            # Fallback to just the module name, but this might lead to table name issues
            return module_name


_registry: Optional[ExtensionRegistry] = None

def discover_extensions(extensions_dir: Path) -> List[str]:
    """
    Global helper to discover extensions.
    """
    global _registry
    if _registry is None:
        _registry = ExtensionRegistry(extensions_dir)
    return _registry.discover_extensions()
