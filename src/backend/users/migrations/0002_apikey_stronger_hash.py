# Manual migration: drop old API keys (SHA-256 hashes cannot be updated), then widen key_hash for Argon2

from django.db import migrations, models


def delete_all_apikeys(apps, schema_editor):
    ApiKey = apps.get_model("users", "ApiKey")
    ApiKey.objects.all().delete()


def noop_reverse(apps, schema_editor):
    pass


class Migration(migrations.Migration):

    dependencies = [
        ("users", "0001_initial"),
    ]

    operations = [
        migrations.RunPython(delete_all_apikeys, noop_reverse),
        migrations.AlterField(
            model_name="apikey",
            name="key_hash",
            field=models.CharField(
                help_text="Argon2 hash of the full API key",
                max_length=255,
            ),
        ),
    ]
