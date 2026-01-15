import os
import sys
from pathlib import Path

# Setup Django environment
sys.path.append(os.getcwd())
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'website.settings')

import django
django.setup()

from website.extensions.extension_loader import ExtensionRegistry
from django.conf import settings

def check_loader():
    ext_dir = Path(settings.BASE_DIR) / 'extensions'
    registry = ExtensionRegistry(ext_dir)
    registry.discover_extensions()
    
    ext = registry.loaded_extensions.get('example_extension')
    if not ext:
        print("Extension 'example_extension' not found!")
        return
        
    print(f"Frontend Entry: {ext.get('frontend_entry')}")

if __name__ == "__main__":
    check_loader()
