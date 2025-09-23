from django.apps import AppConfig
from django.conf import settings

from geo_lib.daemon.database.connection import Database


class DatamanageConfig(AppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'data'

    def ready(self):
        db_cfg = settings.DATABASES.get('default', {})
        params = {
            'host': db_cfg.get('HOST') or None,
            'database': db_cfg.get('NAME'),
            'user': db_cfg.get('USER'),
            'password': db_cfg.get('PASSWORD'),
        }
        port = db_cfg.get('PORT')
        if port:
            params['port'] = port
        try:
            Database.initialise(minconn=1, maxconn=20, **params)
        except Exception:
            # Already initialised or misconfigured; views will raise if unusable
            pass
