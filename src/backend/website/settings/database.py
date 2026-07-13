"""PostgreSQL/PostGIS database configuration, including the dedicated test database."""
from website.config.loader import get_config

_config = get_config()
_db = _config.database

# Build pool options from config. Defaults for 3 workers (3 x 30 = 90 < PostgreSQL 97).
_pool_config = {}
if _db.pool is not None:
    _pool_config = {
        'min_size': _db.pool.min_size,
        'max_size': _db.pool.max_size,
        'timeout': _db.pool.timeout,
    }

# When pooling is enabled, CONN_MAX_AGE must be 0 (pooling doesn't support persistent
# connections). When not pooling, reuse connections for 300s to avoid per-request connect overhead.
_db_config = {
    'ENGINE': 'django.contrib.gis.db.backends.postgis',
    'NAME': _db.name,
    'USER': _db.user,
    'PASSWORD': _db.password,
    'HOST': _db.host,
    'PORT': str(_db.port),
    'CONN_MAX_AGE': 0 if _pool_config else 300,
    # Test database configuration, used by src/tests/run-tests.sh (--reuse-db keeps the
    # database but conftest.py/prepare_test_db.py drop and recreate tables between runs).
    'TEST': {
        'NAME': _db.test.name,
        'USER': _db.test.user,
        'PASSWORD': _db.test.password,
        'HOST': _db.test.host,
        'PORT': str(_db.test.port),
    },
}

if _pool_config:
    _db_config['OPTIONS'] = {'pool': _pool_config}

DATABASES = {
    'default': _db_config,
}
