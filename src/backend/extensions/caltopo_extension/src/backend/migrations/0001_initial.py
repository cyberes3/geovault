# Generated migration for CalTopo extension

import django.db.models.deletion
from django.conf import settings
from django.db import migrations, models
import api.fields


class Migration(migrations.Migration):

    initial = True

    dependencies = [
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.CreateModel(
            name='CalTopoUser',
            fields=[
                ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, primary_key=True, related_name='caltopo_user', serialize=False, to=settings.AUTH_USER_MODEL)),
                ('account_id', models.CharField(help_text='6-character CalTopo account ID', max_length=6)),
                ('credential_id', models.CharField(help_text='12-character CalTopo credential code', max_length=12)),
                ('credential_key', api.fields.EncryptedTextField(help_text='CalTopo credential key (encrypted at rest)')),
                ('imported_features', models.JSONField(default=dict, help_text='Maps CalTopo map_id -> dict of feature_id -> FeatureStore.id. Structure: {map_id: {caltopo_feature_id: geovault_feature_id, ...}, ...}')),
                ('last_synced', models.DateTimeField(blank=True, help_text='Last time CalTopo data was synced', null=True)),
                ('created_at', models.DateTimeField(auto_now_add=True)),
                ('updated_at', models.DateTimeField(auto_now=True)),
            ],
            options={
                'db_table': 'caltopo_extension_caltopouser',
                'indexes': [models.Index(fields=['user'], name='caltopo_ext_user')],
            },
        ),
    ]
