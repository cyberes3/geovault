"""
Single source of truth for search geocoding backend mode names.
Used by config_loader (validation) and backends (registry) to avoid drift.
"""
MAPTILER = 'maptiler'
GOOGLE = 'google'

GEOCODING_SEARCH_MODES = (MAPTILER, GOOGLE)
