#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source $SCRIPT_DIR/venv/bin/activate
python manage.py makemigrations data --no-input
python manage.py migrate --no-input
./manage.py runserver

# Heplful Reminders
# https://172.0.2.105:9191/pgadmin4
# python3 manage.py clear_import_queue --force