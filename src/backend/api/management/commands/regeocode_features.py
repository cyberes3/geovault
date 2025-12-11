"""
Management command to re-geocode all features.
Removes existing reverse geocoding tags and regenerates them using current geocoding service.
"""
from django.core.management.base import BaseCommand
from django.db import transaction

from api.models import FeatureStore
from geo_lib.geocoding.location_tags import get_location_tags
from geo_lib.processing.tagging.modules.geocoding import get_representative_points


class Command(BaseCommand):
    help = 'Re-geocode all features by removing existing reverse geocoding tags and regenerating them'

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

    def handle(self, *args, **options):
        dry_run = options['dry_run']
        batch_size = options['batch_size']
        user_id = options.get('user_id')
        feature_id = options.get('feature_id')
        skip_errors = options['skip_errors']

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN MODE - No changes will be made'))

        # Build query
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

        # Statistics
        processed = 0
        updated = 0
        skipped = 0
        errors = 0

        # List of reverse geocoding tag prefixes to remove
        geocoding_prefixes = [
            'country:',
            'state:',
            'county:',
            'city:',
            'national-park:',
            'national-monument:',
            'national-forest:',
            'national-wildlife-refuge:',
            'national-recreation-area:',
            'national-historic-site:',
            'national-seashore:',
            'national-lakeshore:',
            'state-park:',
            'wilderness:',
            'ski-resort:',
            'lake:',
        ]

        # Process in batches
        for offset in range(0, total_count, batch_size):
            batch = list(queryset[offset:offset + batch_size])
            
            self.stdout.write(f'Processing batch {offset // batch_size + 1} '
                            f'({offset + 1}-{min(offset + batch_size, total_count)} of {total_count})')

            with transaction.atomic():
                for feature_store in batch:
                    try:
                        processed += 1
                        geojson = feature_store.geojson
                        
                        # Get current system tags
                        system_tags = geojson.get('properties', {}).get('system_tags', [])
                        original_tag_count = len(system_tags)
                        
                        # Remove existing reverse geocoding tags
                        filtered_tags = [
                            tag for tag in system_tags
                            if not any(tag.startswith(prefix) for prefix in geocoding_prefixes)
                        ]
                        
                        removed_tags = original_tag_count - len(filtered_tags)
                        
                        # Get geometry type
                        geometry = geojson.get('geometry', {})
                        geometry_type = geometry.get('type', '').lower()
                        
                        # Only geocode points and lines
                        if geometry_type in ['point', 'multipoint', 'linestring', 'multilinestring']:
                            # Create temporary feature object for get_representative_points
                            from geo_lib.types.feature import PointFeature, LineStringFeature
                            
                            if geometry_type in ['point', 'multipoint']:
                                feature_obj = PointFeature(**geojson)
                            else:
                                feature_obj = LineStringFeature(**geojson)
                            
                            # Get representative points
                            points = get_representative_points(feature_obj)
                            
                            if points:
                                # Get location tags for all representative points
                                all_location_tags = set()
                                for lat, lon in points:
                                    try:
                                        location_tags, log_messages = get_location_tags(lat, lon)
                                        all_location_tags.update(location_tags)
                                        
                                        # Display any warnings or errors from geocoding
                                        for log_msg in log_messages:
                                            if log_msg.level == 'ERROR':
                                                self.stdout.write(self.style.ERROR(f'  {log_msg.message}'))
                                            elif log_msg.level == 'WARNING':
                                                self.stdout.write(self.style.WARNING(f'  {log_msg.message}'))
                                    except Exception as e:
                                        self.stdout.write(
                                            self.style.WARNING(
                                                f'  Warning: Failed to geocode point ({lat}, {lon}): {e}'
                                            )
                                        )
                                
                                # Add new geocoding tags
                                filtered_tags.extend(sorted(all_location_tags))
                                new_tags = len(all_location_tags)
                                
                                if removed_tags > 0 or new_tags > 0:
                                    if not dry_run:
                                        # Update the feature
                                        geojson['properties']['system_tags'] = filtered_tags
                                        feature_store.geojson = geojson
                                        feature_store.save()
                                    
                                    updated += 1
                                    self.stdout.write(
                                        f'  Feature {feature_store.id}: removed {removed_tags} tags, '
                                        f'added {new_tags} tags'
                                    )
                                else:
                                    skipped += 1
                            else:
                                # No points to geocode
                                if removed_tags > 0:
                                    if not dry_run:
                                        geojson['properties']['system_tags'] = filtered_tags
                                        feature_store.geojson = geojson
                                        feature_store.save()
                                    updated += 1
                                    self.stdout.write(
                                        f'  Feature {feature_store.id}: removed {removed_tags} tags, no new tags'
                                    )
                                else:
                                    skipped += 1
                        else:
                            # Polygon - just remove old tags if any
                            if removed_tags > 0:
                                if not dry_run:
                                    geojson['properties']['system_tags'] = filtered_tags
                                    feature_store.geojson = geojson
                                    feature_store.save()
                                updated += 1
                                self.stdout.write(
                                    f'  Feature {feature_store.id} (polygon): removed {removed_tags} tags'
                                )
                            else:
                                skipped += 1
                    
                    except Exception as e:
                        errors += 1
                        error_msg = f'  ERROR processing feature {feature_store.id}: {e}'
                        
                        if skip_errors:
                            self.stdout.write(self.style.ERROR(error_msg))
                            continue
                        else:
                            self.stdout.write(self.style.ERROR(error_msg))
                            raise

        # Print summary
        self.stdout.write('')
        self.stdout.write(self.style.SUCCESS('=' * 60))
        self.stdout.write(self.style.SUCCESS('Re-geocoding complete!'))
        self.stdout.write('')
        self.stdout.write(f'Total features processed: {processed}')
        self.stdout.write(f'Features updated: {updated}')
        self.stdout.write(f'Features skipped (no changes): {skipped}')
        if errors > 0:
            self.stdout.write(self.style.WARNING(f'Errors encountered: {errors}'))
        self.stdout.write(self.style.SUCCESS('=' * 60))

