# Generated manually

from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [
        ('api', '0002_add_allow_downloads_to_shares'),
    ]

    operations = [
        migrations.RemoveField(
            model_name='tagshare',
            name='use_tag_as_id',
        ),
    ]

