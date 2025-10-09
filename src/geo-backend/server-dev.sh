#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source $SCRIPT_DIR/venv/bin/activate
python manage.py makemigrations data --no-input
python manage.py migrate --no-input

# Run startup checks before starting the server
echo "Running startup checks..."
python manage.py startup_checks

./manage.py runserver

# Heplful Reminders
# https://172.0.2.105:9191/pgadmin4
# python3 manage.py clear_import_queue --force
# python manage.py clear_all_data --confirm