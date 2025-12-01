#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source "$SCRIPT_DIR"/venv/bin/activate
python manage.py makemigrations api --no-input
python manage.py makemigrations users --no-input
python manage.py migrate --no-input

# REMINDER: you won't get static file logs or headers in the debug server!!!!!!
# STOP TRYING TO FIX IT!!!!!!!!!!!

python manage.py runserver 0.0.0.0:8000

# Heplful Reminders
# https://172.0.2.105:9191/pgadmin4
# python3 manage.py clear_import_queue --force
# python3 manage.py clear_all_data --confirm
# python3 manage.py drop_all_tables