#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source "$SCRIPT_DIR"/venv/bin/activate
python manage.py makemigrations api --no-input
python manage.py makemigrations users --no-input
python manage.py migrate --no-input

# REMINDER: you won't get static file logs or headers in the debug server!!!!!!
# STOP TRYING TO FIX IT!!!!!!!!!!!

# Run Django dev server
python -u manage.py runserver 0.0.0.0:8000

# Run Celery in separate terminals:
# `default` is included as a safety net for any future task that omits an explicit `queue=`
# (Celery's CELERY_TASK_DEFAULT_QUEUE) - no task targets it today, but a worker not listening on
# it means such a task would silently never run instead of erroring.
# `--concurrency=4` is a sensible starting point (see installation/geovault-celery.service for
# the full rationale); imports are serialized per-user via a Redis lock, so this just bounds how
# many different users' imports can run at once.
# celery -A website.celery_app worker --loglevel=info --queues=default,maintenance,extensions,live_track,imports --concurrency=4
# celery -A website.celery_app beat --loglevel=info

# Heplful Reminders
# https://172.0.3.105/pgadmin4
# python3 manage.py clear_import_queue --force
# python3 manage.py clear_all_data --confirm
# python3 manage.py drop_all_tables