# Generated migration to rename raw_kml to raw_file

from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [
        ('api', '0004_alter_importqueue_geojson_hash'),
    ]

    operations = [
        migrations.RenameField(
            model_name='importqueue',
            old_name='raw_kml',
            new_name='raw_file',
        ),
    ]

