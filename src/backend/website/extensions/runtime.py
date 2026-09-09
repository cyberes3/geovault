"""
Post-app-load extension initialization.

Calls every ExtensionAppConfig.extension_ready(), then binds periodic Celery
schedules. Silent skip only for the runserver reloader parent and migrate.
"""
import logging
import os
import sys

from website.extensions.capabilities import (
    clear_extension_context,
    get_registered_periodic_bg_tasks,
    set_extension_context,
)
from website.extensions.extension_base import ExtensionAppConfig

logger = logging.getLogger("website.extensions.runtime")


class ExtensionRuntime:
    _initialized = False
    _periodic_bound = False

    @classmethod
    def should_skip(cls) -> bool:
        run_main = os.environ.get("RUN_MAIN")
        is_runserver = any(cmd in sys.argv for cmd in ("runserver", "runserver_plus"))
        if is_runserver and run_main != "true":
            return True
        if not is_runserver and run_main is not None and run_main != "true":
            return True
        if "migrate" in sys.argv or "makemigrations" in sys.argv:
            return True
        return False

    @classmethod
    def initialize(cls) -> None:
        if cls._initialized:
            return
        if cls.should_skip():
            return

        from django.apps import apps

        for app_config in apps.get_app_configs():
            if not isinstance(app_config, ExtensionAppConfig):
                continue
            extension_name = app_config.label or app_config.name.split(".")[-1]
            set_extension_context(extension_name)
            try:
                logger.info("Initializing extension: %s", extension_name)
                app_config.extension_ready()
                logger.info("Extension '%s' initialized successfully", extension_name)
            except Exception as exc:
                logger.error(
                    "Error initializing extension '%s': %s",
                    extension_name,
                    exc,
                    exc_info=True,
                )
            finally:
                clear_extension_context()

        cls._initialized = True
        cls.bind_periodic_tasks()

    @classmethod
    def bind_periodic_tasks(cls) -> None:
        """Register collected periodic schedules after every extension_ready()."""
        if cls._periodic_bound or not cls._initialized:
            return

        from website.celery_app import celery_app

        for item in get_registered_periodic_bg_tasks():
            task_name = item["task_name"]
            schedule_name = item["schedule_name"]
            task = celery_app.tasks.get(task_name)
            if task is None:
                logger.warning(
                    "Skipping extension periodic task '%s': task '%s' not found",
                    schedule_name,
                    task_name,
                )
                continue
            celery_app.add_periodic_task(
                item["schedule"],
                task.s(*item["args"], **item["kwargs"]),
                name=schedule_name,
                **item["options"],
            )
        cls._periodic_bound = True
