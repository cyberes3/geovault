"""
Management command to print a feature's geometry/coordinates by ID.
Useful when the API returns 404 (e.g. feature owned by another user) but you need coords for debugging.
"""
import json

from django.core.management.base import BaseCommand

from api.models import FeatureStore


class Command(BaseCommand):
    help = 'Print feature geometry and coordinates by feature ID (no user filter, for debugging)'

    def add_arguments(self, parser):
        parser.add_argument('feature_id', type=int, help='Feature store ID (e.g. 563)')
        parser.add_argument(
            '--user-id',
            type=int,
            default=None,
            help='If set, only return the feature when it belongs to this user (matches API behavior)',
        )

    def handle(self, *args, **options):
        feature_id = options['feature_id']
        user_id = options['user_id']

        qs = FeatureStore.objects.filter(id=feature_id)
        if user_id is not None:
            qs = qs.filter(user_id=user_id)
        feature = qs.first()

        if not feature:
            if user_id is not None:
                self.stdout.write(self.style.ERROR(f'Feature {feature_id} not found or not owned by user {user_id}'))
            else:
                self.stdout.write(self.style.ERROR(f'Feature {feature_id} not found'))
            return

        geom = feature.geojson.get('geometry') if feature.geojson else None
        if not geom:
            self.stdout.write(self.style.ERROR(f'Feature {feature_id} has no geometry in geojson'))
            return

        geom_type = geom.get('type', '')
        coords = geom.get('coordinates')
        if geom_type in ('LineString', 'MultiLineString', 'Polygon', 'MultiPolygon') and coords is not None:
            if geom_type == 'LineString':
                coords = coords[:3]
            elif geom_type == 'MultiLineString':
                coords = [coords[0][:3]] if coords else []
            elif geom_type == 'Polygon':
                coords = [coords[0][:3]] if coords and coords[0] else []
            else:  # MultiPolygon
                coords = [coords[0][0][:3]] if coords and coords[0] and coords[0][0] else []

        self.stdout.write(f'Feature {feature_id} (user_id={feature.user_id})')
        self.stdout.write(f'  type: {geom_type}')
        self.stdout.write(f'  coordinates: {json.dumps(coords, indent=4)}')
