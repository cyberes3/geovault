#!/bin/bash
python manage.py makemigrations data --no-input
python manage.py migrate --no-input
./manage.py runserver