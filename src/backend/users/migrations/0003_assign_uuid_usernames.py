# Data migration: assign UUID usernames to existing users whose username
# is not already a UUID (e.g. email-derived values like "drake.panzer").

import re
import uuid

from django.conf import settings
from django.db import migrations


# Same pattern as the signup test: 8-4-4-4-12 hex with hyphens
UUID_USERNAME_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z",
    re.IGNORECASE,
)


def assign_uuid_usernames(apps, schema_editor):
    app_label, model_name = settings.AUTH_USER_MODEL.rsplit(".", 1)
    User = apps.get_model(app_label, model_name)
    for user in User.objects.all():
        if not UUID_USERNAME_RE.match(user.username):
            user.username = str(uuid.uuid4())
            user.save(update_fields=["username"])


def noop_reverse(apps, schema_editor):
    pass


class Migration(migrations.Migration):

    dependencies = [
        ("users", "0002_apikey_stronger_hash"),
    ]

    operations = [
        migrations.RunPython(assign_uuid_usernames, noop_reverse),
    ]
