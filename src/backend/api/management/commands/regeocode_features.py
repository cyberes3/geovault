"""
Management command to re-reverse-geocode all features.
Removes existing reverse geocoding tags and regenerates them using current reverse geocoding service.
"""
from django.core.management.base import BaseCommand
from django.db import transaction

from api.models import FeatureStore
from api.services.regeocode_service import RegeocodeService, TagRegenerationResult


class Command(BaseCommand):
    help = 'Re-reverse-geocode all features by removing existing reverse geocoding tags and regenerating them'

    def add_arguments(self, parser):
        parser.add_argument(
            '--dry-run',
            action='store_true',
            help='Show what would be changed without actually making changes',
        )
        parser.add_argument(
            '--batch-size',
            type=int,
            default=100,
            help='Number of features to process in each batch (default: 100)',
        )
        parser.add_argument(
            '--user-id',
            type=int,
            help='Only re-geocode features for a specific user ID',
        )
        parser.add_argument(
            '--feature-id',
            type=int,
            help='Only re-geocode a specific feature by ID',
        )
        parser.add_argument(
            '--skip-errors',
            action='store_true',
            help='Continue processing even if individual features fail',
        )
        parser.add_argument(
            '--skip-reverse-geocoding',
            action='store_true',
            help='Skip reverse geocoding and only regenerate other tags. Preserves existing reverse geocoding tags.',
        )

    def handle(self, *args, **options):
        dry_run = options['dry_run']
        batch_size = options['batch_size']
        user_id = options.get('user_id')
        feature_id = options.get('feature_id')
        skip_errors = options['skip_errors']
        skip_reverse_geocoding = options['skip_reverse_geocoding']
        service = RegeocodeService()

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN MODE - No changes will be made'))
        if skip_reverse_geocoding:
            self.stdout.write(self.style.WARNING(
                'SKIP REVERSE GEOCODING MODE - Only regenerating other tags, preserving reverse geocoding tags'
            ))

        queryset = FeatureStore.objects.all()
        if user_id:
            queryset = queryset.filter(user_id=user_id)
            self.stdout.write(f'Filtering to user ID: {user_id}')
        if feature_id:
            queryset = queryset.filter(id=feature_id)
            self.stdout.write(f'Processing single feature ID: {feature_id}')

        total_count = queryset.count()
        if total_count == 0:
            self.stdout.write(self.style.WARNING('No features found to process'))
            return
        self.stdout.write(f'Found {total_count} features to re-geocode')

        processed = updated = skipped = errors = 0

        for offset in range(0, total_count, batch_size):
            batch = list(queryset[offset:offset + batch_size])
            self.stdout.write(
                f'Processing batch {offset // batch_size + 1} '
                f'({offset + 1}-{min(offset + batch_size, total_count)} of {total_count})'
            )

            with transaction.atomic():
                for feature_store in batch:
                    processed += 1
                    try:
                        result = self._regenerate_feature_tags(service, feature_store, dry_run, skip_reverse_geocoding)
                    except Exception as e:
                        errors += 1
                        self.stdout.write(self.style.ERROR(f'  ERROR processing feature {feature_store.id}: {e}'))
                        if not skip_errors:
                            raise
                        continue

                    for warning in result.warnings:
                        self.stdout.write(self.style.WARNING(f'  {warning}'))

                    if result.updated:
                        updated += 1
                        self.stdout.write(f'  Feature {feature_store.id}: {result.message}')
                        if result.removed_tags:
                            self.stdout.write(self.style.WARNING(f'    Removed: {", ".join(result.removed_tags)}'))
                        if result.added_tags:
                            self.stdout.write(self.style.SUCCESS(f'    Added: {", ".join(result.added_tags)}'))
                    else:
                        skipped += 1

        self.stdout.write('')
        self.stdout.write(self.style.SUCCESS('=' * 60))
        if skip_reverse_geocoding:
            self.stdout.write(self.style.SUCCESS('Tag regeneration complete!'))
        else:
            self.stdout.write(self.style.SUCCESS('Re-reverse-geocoding complete!'))
        self.stdout.write('')
        self.stdout.write(f'Total features processed: {processed}')
        self.stdout.write(f'Features updated: {updated}')
        self.stdout.write(f'Features skipped (no changes): {skipped}')
        if errors > 0:
            self.stdout.write(self.style.WARNING(f'Errors encountered: {errors}'))
        self.stdout.write(self.style.SUCCESS('=' * 60))

    @staticmethod
    def _regenerate_feature_tags(
        service: RegeocodeService,
        feature_store: FeatureStore,
        dry_run: bool,
        skip_reverse_geocoding: bool,
    ) -> TagRegenerationResult:
        geojson = feature_store.geojson
        props = geojson.get('properties') or {}
        if props.get('geojson_hash') is None and feature_store.geojson_hash:
            props['geojson_hash'] = feature_store.geojson_hash
            geojson['properties'] = props
        system_tags = props.get('system_tags', [])

        if skip_reverse_geocoding:
            preserved_geocoding_tags, _ = service.separate_tags(system_tags)
            return service.regenerate_other_tags(feature_store, geojson, preserved_geocoding_tags, dry_run)

        _, filtered_tags = service.separate_tags(system_tags)
        return service.regenerate_geocoding_tags(feature_store, geojson, filtered_tags, dry_run)
