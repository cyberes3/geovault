"""
Management command to re-reverse-geocode all features.
Removes existing reverse geocoding tags and regenerates them using current reverse geocoding service.
"""
from django.core.management.base import BaseCommand
from django.db import transaction

from api.models import FeatureStore
from geo_lib.reverse_geocoding.constants import REVERSE_GEOCODING_TAG_PREFIXES
from geo_lib.reverse_geocoding.location_tags import get_location_tags
from geo_lib.processing.logging import ImportLog
from geo_lib.processing.tagging.generate import generate_auto_tags
from geo_lib.processing.tagging.modules.reverse_geocoding import get_representative_points
from geo_lib.types.feature import (
    PointFeature, LineStringFeature, MultiLineStringFeature, PolygonFeature
)


class Command(BaseCommand):
    help = 'Re-reverse-geocode all features by removing existing reverse geocoding tags and regenerating them'

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        # List of reverse geocoding tag prefixes with colons for matching
        self.geocoding_prefixes = [f"{prefix}:" for prefix in REVERSE_GEOCODING_TAG_PREFIXES]

    def _get_feature_class(self, geometry_type: str):
        """Get the appropriate feature class for a geometry type."""
        match geometry_type.lower():
            case 'point' | 'multipoint':
                return PointFeature
            case 'linestring':
                return LineStringFeature
            case 'multilinestring':
                return MultiLineStringFeature
            case 'polygon' | 'multipolygon':
                return PolygonFeature
            case _:
                return None

    def _separate_tags(self, system_tags: list):
        """Separate reverse geocoding tags from other system tags."""
        geocoding_tags = [
            tag for tag in system_tags
            if any(tag.startswith(prefix) for prefix in self.geocoding_prefixes)
        ]
        other_tags = [
            tag for tag in system_tags
            if not any(tag.startswith(prefix) for prefix in self.geocoding_prefixes)
        ]
        return geocoding_tags, other_tags

    def _update_feature_tags(self, feature_store, geojson, new_tags, dry_run):
        """Update feature with new tags if not in dry-run mode."""
        if not dry_run:
            geojson['properties']['system_tags'] = new_tags
            feature_store.geojson = geojson
            feature_store.save()

    def _regenerate_other_tags(self, feature_store, geojson, preserved_geocoding_tags, dry_run):
        """Regenerate non-reverse_geocoding tags while preserving reverse geocoding tags."""
        geometry_type = geojson.get('geometry', {}).get('type', '').lower()
        feature_class = self._get_feature_class(geometry_type)
        
        if not feature_class:
            # Unsupported geometry type - preserve all existing tags
            old_other_tags = [
                tag for tag in geojson.get('properties', {}).get('system_tags', [])
                if not any(tag.startswith(prefix) for prefix in self.geocoding_prefixes)
            ]
            all_tags = sorted(set(old_other_tags + preserved_geocoding_tags))
            # No changes needed, just return
            return False, None
        
        # Ensure geojson_hash is present
        geojson.setdefault('properties', {})['geojson_hash'] = feature_store.geojson_hash
        
        try:
            feature_instance = feature_class(**geojson)
            import_log = ImportLog()
            # Note: We don't pass filename or file_content, so source-file and source-device
            # tags won't be regenerated. We preserve all existing tags.
            new_tags = generate_auto_tags(feature_instance, import_log, skip_reverse_geocoding=True)
            
            # Get all existing non-reverse_geocoding tags (preserve all of them)
            old_other_tags = [
                tag for tag in geojson.get('properties', {}).get('system_tags', [])
                if not any(tag.startswith(prefix) for prefix in self.geocoding_prefixes)
            ]
            
            # Union: keep all old tags and add any new ones (never remove)
            all_other_tags = sorted(set(old_other_tags + new_tags))
            
            # Combine with preserved reverse geocoding tags
            all_tags = sorted(set(all_other_tags + preserved_geocoding_tags))
            
            # Check if anything changed (new tags were added)
            old_set = set(old_other_tags)
            new_set = set(new_tags)
            added = len(new_set - old_set)  # Only count truly new tags
            
            if added > 0 or sorted(set(old_other_tags)) != all_other_tags:
                self._update_feature_tags(feature_store, geojson, all_tags, dry_run)
                parts = []
                if added > 0:
                    parts.append(f'added {added}')
                if len(preserved_geocoding_tags) > 0:
                    parts.append(f'preserved {len(preserved_geocoding_tags)} reverse geocoding')
                msg = f'regenerated other tags ({", ".join(parts)})' if parts else 'regenerated other tags'
                
                # Display warnings/errors from tag generation
                for log_msg in import_log.get():
                    if log_msg.level.value >= 30:
                        style = self.style.ERROR if log_msg.level.value >= 40 else self.style.WARNING
                        self.stdout.write(style(f'  {log_msg.msg}'))
                
                return True, msg
            return False, None
            
        except Exception as e:
            raise Exception(f'Failed to generate tags: {e}')

    def _regenerate_geocoding_tags(self, feature_store, geojson, filtered_tags, dry_run):
        """Regenerate reverse geocoding tags (original mode). Returns (updated, msg, removed_tags, added_tags)."""
        original_tags = geojson.get('properties', {}).get('system_tags', [])
        original_geocoding_tags, _ = self._separate_tags(original_tags)
        geometry_type = geojson.get('geometry', {}).get('type', '').lower()
        
        if geometry_type not in ['point', 'multipoint', 'linestring', 'multilinestring']:
            # Polygon - just remove old reverse_geocoding tags if any
            if len(original_geocoding_tags) > 0:
                self._update_feature_tags(feature_store, geojson, filtered_tags, dry_run)
                return True, f'removed {len(original_geocoding_tags)} tags', sorted(original_geocoding_tags), []
            return False, None, [], []
        
        # Create feature object for get_representative_points
        feature_class = PointFeature if geometry_type in ['point', 'multipoint'] else LineStringFeature
        feature_obj = feature_class(**geojson)
        points = get_representative_points(feature_obj)
        
        if not points:
            if len(original_geocoding_tags) > 0:
                self._update_feature_tags(feature_store, geojson, filtered_tags, dry_run)
                return True, f'removed {len(original_geocoding_tags)} tags, no new tags', sorted(original_geocoding_tags), []
            return False, None, [], []
        
        # Get location tags for all representative points
        all_location_tags = set()
        for lat, lon in points:
            try:
                location_tags, log_messages = get_location_tags(lat, lon)
                all_location_tags.update(location_tags)
                
                # Display warnings/errors from reverse_geocoding
                for log_msg in log_messages:
                    if log_msg.level == 'ERROR':
                        self.stdout.write(self.style.ERROR(f'  {log_msg.message}'))
                    elif log_msg.level == 'WARNING':
                        self.stdout.write(self.style.WARNING(f'  {log_msg.message}'))
            except Exception as e:
                self.stdout.write(self.style.WARNING(f'  Warning: Failed to geocode point ({lat}, {lon}): {e}'))
        
        # Compute net changes: only report tags that actually changed
        original_set = set(original_geocoding_tags)
        new_set = all_location_tags
        net_removed = sorted(original_set - new_set)
        net_added = sorted(new_set - original_set)

        # Only update and report if there is an actual change
        if net_removed or net_added:
            added_tags_sorted = sorted(all_location_tags)
            filtered_tags.extend(added_tags_sorted)
            self._update_feature_tags(feature_store, geojson, filtered_tags, dry_run)
            msg_parts = []
            if net_removed:
                msg_parts.append(f'removed {len(net_removed)} tags')
            if net_added:
                msg_parts.append(f'added {len(net_added)} tags')
            return True, ', '.join(msg_parts), net_removed, net_added
        return False, None, [], []

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

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN MODE - No changes will be made'))
        
        if skip_reverse_geocoding:
            self.stdout.write(self.style.WARNING('SKIP REVERSE GEOCODING MODE - Only regenerating other tags, preserving reverse geocoding tags'))

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
                        props = geojson.get('properties') or {}
                        if props.get('geojson_hash') is None and feature_store.geojson_hash:
                            props['geojson_hash'] = feature_store.geojson_hash
                            geojson['properties'] = props
                        system_tags = props.get('system_tags', [])
                        
                        if skip_reverse_geocoding:
                            # Mode: Skip reverse geocoding, only regenerate other tags
                            preserved_geocoding_tags, _ = self._separate_tags(system_tags)
                            try:
                                was_updated, msg = self._regenerate_other_tags(
                                    feature_store, geojson, preserved_geocoding_tags, dry_run
                                )
                                if was_updated:
                                    updated += 1
                                    self.stdout.write(f'  Feature {feature_store.id}: {msg}')
                                else:
                                    skipped += 1
                            except Exception as e:
                                errors += 1
                                self.stdout.write(self.style.ERROR(f'  ERROR processing feature {feature_store.id}: {e}'))
                                if not skip_errors:
                                    raise
                        else:
                            # Original mode: Re-geocode reverse geocoding tags
                            _, filtered_tags = self._separate_tags(system_tags)
                            try:
                                was_updated, msg, removed_tags, added_tags = self._regenerate_geocoding_tags(
                                    feature_store, geojson, filtered_tags, dry_run
                                )
                                if was_updated:
                                    updated += 1
                                    self.stdout.write(f'  Feature {feature_store.id}: {msg}')
                                    if removed_tags:
                                        self.stdout.write(f'    Removed: {", ".join(removed_tags)}')
                                    if added_tags:
                                        self.stdout.write(f'    Added: {", ".join(added_tags)}')
                                else:
                                    skipped += 1
                            except Exception as e:
                                errors += 1
                                self.stdout.write(self.style.ERROR(f'  ERROR processing feature {feature_store.id}: {e}'))
                                if not skip_errors:
                                    raise
                    
                    except Exception as e:
                        errors += 1
                        error_msg = f'  ERROR processing feature {feature_store.id}: {e}'
                        self.stdout.write(self.style.ERROR(error_msg))
                        if not skip_errors:
                            raise

        # Print summary
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

