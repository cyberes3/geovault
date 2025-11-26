# Generated manually

from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('api', '0001_initial'),
    ]

    operations = [
        migrations.AddField(
            model_name='tagshare',
            name='allow_downloads',
            field=models.BooleanField(default=False, help_text='Whether viewers can download features as KMZ'),
        ),
        migrations.AddField(
            model_name='collectionshare',
            name='allow_downloads',
            field=models.BooleanField(default=False, help_text='Whether viewers can download features as KMZ'),
        ),
    ]

