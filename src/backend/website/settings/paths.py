"""
Filesystem paths needed before any config loading happens.

BASE_DIR/EXTENSIONS_DIR must be importable from `website.settings` (the package) before
`app_config.py` runs extension discovery, which imports extension apps.py modules that may
themselves read `django.conf.settings.BASE_DIR` at import time (e.g. to build a data
directory path). Django's settings LazySettings re-enters `importlib.import_module(
'website.settings')` on that access; since the package is already mid-import at that point,
the reentrant import returns the partial module as-is rather than re-running it, so BASE_DIR
must already be bound on the package namespace by then -- hence this being `settings/__init__.py`'s
first import, ahead of `app_config`.
"""
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent.parent

EXTENSIONS_DIR = BASE_DIR / 'extensions'
if str(EXTENSIONS_DIR) not in sys.path:
    sys.path.insert(0, str(EXTENSIONS_DIR))
