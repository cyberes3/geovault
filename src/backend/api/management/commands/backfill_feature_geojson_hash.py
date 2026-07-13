"""
Backfill geojson_hash for features that are missing it in stored geojson['properties'].
Uses FeatureStore.geojson_hash when present; otherwise generates from current geojson and sets both.
Makes existing features (e.g. places created before properties.geojson_hash was set) valid for pydantic.
"""
from django.core.management.base import BaseCommand
from django.db import IntegrityError

from api.models import FeatureStore
from geo_lib.feature_id import generate_geojson_hash


class Command(BaseCommand):
    help = 'Backfill geojson_hash in properties for features missing it (e.g. old places). Uses row hash or generates.'

    def add_arguments(self, parser):
        parser.add_argument(
            '--dry-run',
            action='store_true',
            help='Only report how many features would be updated',
        )
        parser.add_argument(
            '--scope',
            type=str,
            default=None,
            help='Only process features with this scope (e.g. places). Default: all.',
        )

    def handle(self, *args, **options):
        dry_run = options['dry_run']
        scope = options['scope']

        qs = FeatureStore.objects.all()
        if scope is not None:
            qs = qs.filter(scope=scope)
            self.stdout.write(f'Scope filter: {scope!r}')

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN - No changes will be saved'))

        updated_from_column = 0
        updated_generated = 0
        skipped_duplicate = 0
        errors = 0

        for feature in qs.iterator():
            geojson = feature.geojson
            if not geojson:
                continue
            props = geojson.get('properties') or {}
            if props.get('geojson_hash'):
                continue

            if feature.geojson_hash:
                props['geojson_hash'] = feature.geojson_hash
                geojson['properties'] = props
                if not dry_run:
                    feature.geojson = geojson
                    feature.save(update_fields=['geojson'])
                updated_from_column += 1
                continue

            try:
                new_hash = generate_geojson_hash(geojson)
                props['geojson_hash'] = new_hash
                geojson['properties'] = props
                if not dry_run:
                    feature.geojson = geojson
                    feature.geojson_hash = new_hash
                    feature.save(update_fields=['geojson', 'geojson_hash'])
                updated_generated += 1
            except IntegrityError as e:
                if 'unique_user_geojson_hash' in str(e):
                    skipped_duplicate += 1
                else:
                    errors += 1
                    self.stdout.write(self.style.ERROR(f'  Feature {feature.id}: {e}'))
            except Exception as e:
                errors += 1
                self.stdout.write(self.style.ERROR(f'  Feature {feature.id}: {e}'))

        self.stdout.write('Backfill complete.')
        self.stdout.write(f'  Set from column (geojson_hash): {updated_from_column}')
        self.stdout.write(f'  Generated and set: {updated_generated}')
        if skipped_duplicate:
            self.stdout.write(self.style.WARNING(f'  Skipped (duplicate hash): {skipped_duplicate}'))
        if errors:
            self.stdout.write(self.style.ERROR(f'  Errors: {errors}'))
