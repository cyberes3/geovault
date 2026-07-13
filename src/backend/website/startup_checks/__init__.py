"""
Startup checks for the GeoVault Django application, split by concern:
- environment: Python version, database, PostGIS, tables, Redis, writable directories
- assets: built frontend files, generated font glyphs
- config: config file/site/email/MaxMind/tile-source/file-type validation
- celery: worker and beat reachability

`website.startup_operations` (a sibling module, not part of this package) holds the
destructive/mutating startup actions (cache clearing, job recovery) that aren't checks.
`orchestrator.run_startup_checks()` ties everything together and is what callers
(wsgi.py, asgi.py, the management command) should import.
"""
