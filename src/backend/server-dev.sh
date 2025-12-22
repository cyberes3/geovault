#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source "$SCRIPT_DIR"/venv/bin/activate
python manage.py makemigrations api --no-input
python manage.py makemigrations users --no-input
python manage.py migrate --no-input

# REMINDER: you won't get static file logs or headers in the debug server!!!!!!
# STOP TRYING TO FIX IT!!!!!!!!!!!

# Run Django dev server
# Note: Django's autoreloader automatically ignores __pycache__, *.pyc, and other common patterns
# Static file watching is handled by WhiteNoise (which we've optimized in settings.py)
# Large icon directories are excluded from WhiteNoise auto-refresh to improve performance
# Use -u flag for unbuffered output to ensure logs appear immediately
python -u manage.py runserver 0.0.0.0:8000

# Heplful Reminders
# https://172.0.3.105/pgadmin4
# python3 manage.py clear_import_queue --force
# python3 manage.py clear_all_data --confirm
# python3 manage.py drop_all_tables