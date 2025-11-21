"""
YAML Configuration Loader for GeoServer

Loads configuration from a YAML file and provides it to Django settings.
Supports environment variable overrides for sensitive values.
"""
import os
import yaml
from pathlib import Path
from typing import Dict, Any, Optional
from geo_lib.logging.console import get_config_logger

logger = get_config_logger()


class ConfigLoader:
    """Loads and manages YAML configuration for the application."""
    
    def __init__(self, config_path: Optional[str] = None):
        """
        Initialize the config loader.
        
        Args:
            config_path: Path to the YAML config file. If None, will look for
                        config.yaml in the project root or use GEOSERVER_CONFIG_PATH env var.
        """
        if config_path is None:
            # Check environment variable first
            config_path = os.environ.get('GEOSERVER_CONFIG_PATH')
            
            # If not set, default to config.yaml in project root (geo-backend directory)
            if config_path is None:
                # Get the directory containing this file (website/)
                current_dir = Path(__file__).resolve().parent
                # Go up to geo-backend directory
                project_root = current_dir.parent
                config_path = project_root / 'config.yaml'
            else:
                config_path = Path(config_path)
        else:
            config_path = Path(config_path)
        
        self.config_path = config_path
        self.config: Dict[str, Any] = {}
        self._load_config()
    
    def _load_config(self):
        """Load configuration from YAML file."""
        if not self.config_path.exists():
            logger.warning(
                f"Config file not found at {self.config_path}. "
                "Using default values. Create config.yaml or set GEOSERVER_CONFIG_PATH."
            )
            self.config = {}
            return
        
        try:
            with open(self.config_path, 'r', encoding='utf-8') as f:
                self.config = yaml.safe_load(f) or {}
            logger.info(f"Loaded configuration from {self.config_path}")
        except yaml.YAMLError as e:
            logger.error(f"Error parsing YAML config file {self.config_path}: {e}")
            self.config = {}
        except Exception as e:
            logger.error(f"Error loading config file {self.config_path}: {e}")
            self.config = {}
    
    def get(self, key_path: str, default: Any = None) -> Any:
        """
        Get a configuration value using dot-notation path.
        
        Args:
            key_path: Dot-separated path to the config value (e.g., 'database.host')
            default: Default value if key is not found
        
        Returns:
            Configuration value or default
        """
        keys = key_path.split('.')
        value = self.config
        
        for key in keys:
            if isinstance(value, dict) and key in value:
                value = value[key]
            else:
                return default
        
        return value
    
    def get_bool(self, key_path: str, default: bool = False) -> bool:
        """Get a boolean configuration value."""
        value = self.get(key_path, default)
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            return value.lower() in ('true', '1', 'yes', 'on')
        return bool(value)
    
    def get_int(self, key_path: str, default: int = 0) -> int:
        """Get an integer configuration value."""
        value = self.get(key_path, default)
        try:
            return int(value)
        except (ValueError, TypeError):
            return default
    
    def get_float(self, key_path: str, default: float = 0.0) -> float:
        """Get a float configuration value."""
        value = self.get(key_path, default)
        try:
            return float(value)
        except (ValueError, TypeError):
            return default
    
    def get_list(self, key_path: str, default: Optional[list] = None) -> list:
        """Get a list configuration value."""
        if default is None:
            default = []
        value = self.get(key_path, default)
        if isinstance(value, list):
            return value
        return default
    
    def get_str(self, key_path: str, default: str = '') -> str:
        """Get a string configuration value."""
        value = self.get(key_path, default)
        if value is None:
            return default
        return str(value)
    
    def get_with_env_override(self, key_path: str, env_var: str, default: Any = None) -> Any:
        """
        Get a configuration value with environment variable override.
        
        Args:
            key_path: Dot-separated path to the config value
            env_var: Environment variable name to check for override
            default: Default value if neither config nor env var is set
        
        Returns:
            Environment variable value if set, otherwise config value, otherwise default
        """
        # Check environment variable first (highest priority)
        if env_var in os.environ:
            return os.environ[env_var]
        
        # Fall back to config file
        return self.get(key_path, default)
    
    def get_bool_with_env_override(self, key_path: str, env_var: str, default: bool = False) -> bool:
        """
        Get a boolean configuration value with environment variable override.
        
        Args:
            key_path: Dot-separated path to the config value
            env_var: Environment variable name to check for override
            default: Default value if neither config nor env var is set
        
        Returns:
            Boolean value from environment variable, config file, or default
        """
        # Check environment variable first (highest priority)
        if env_var in os.environ:
            value = os.environ[env_var]
            if isinstance(value, str):
                return value.lower() in ('true', '1', 'yes', 'on')
            return bool(value)
        
        # Fall back to config file
        return self.get_bool(key_path, default)


# Global config loader instance
_config_loader: Optional[ConfigLoader] = None


def get_config_loader() -> ConfigLoader:
    """Get the global config loader instance."""
    global _config_loader
    if _config_loader is None:
        _config_loader = ConfigLoader()
    return _config_loader

