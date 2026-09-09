"""DuplicateDetector is main_map only; replacement drafts are excluded."""

from datetime import datetime, timezone

import pytest
from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point

from api.models import FeatureStore, ImportDraftFeature, ImportQueue
from geo_lib.duplicates.adapters.import_draft import build_draft_hash_index, build_duplicate_index
from geo_lib.duplicates.detector import DuplicateDetector
from geo_lib.duplicates.verdict import VerdictKind, VerdictScope
from geo_lib.feature_id import generate_geojson_hash

User = get_user_model()


def _point(name: str, lon: float = 0.0, lat: float = 0.0) -> dict:
    feature = {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [lon, lat, 0.0]},
        'properties': {'name': name},
    }
    feature['properties']['geojson_hash'] = generate_geojson_hash(feature)
    return feature


@pytest.mark.django_db
def test_places_scoped_feature_is_not_a_library_hash_hit():
    user = User.objects.create_user(username='dup-places', email='dup-places@example.com', password='x')
    incoming = _point('places-match')
    FeatureStore.objects.create(
        user=user,
        geojson=incoming,
        geojson_hash=incoming['properties']['geojson_hash'],
        geometry=Point(0.0, 0.0, 0.0, srid=4326),
        scope='places',
    )
    index = build_duplicate_index(user.id, [incoming], 0, datetime.now(timezone.utc))
    verdicts = DuplicateDetector().detect_two_pass([incoming], index)
    digest = incoming['properties']['geojson_hash']
    assert verdicts[digest].kind == VerdictKind.NONE


@pytest.mark.django_db
def test_main_map_hash_is_a_library_hit():
    user = User.objects.create_user(username='dup-main', email='dup-main@example.com', password='x')
    incoming = _point('library-match', lon=10.0, lat=20.0)
    FeatureStore.objects.create(
        user=user,
        geojson=incoming,
        geojson_hash=incoming['properties']['geojson_hash'],
        geometry=Point(10.0, 20.0, 0.0, srid=4326),
        scope=None,
    )
    index = build_duplicate_index(user.id, [incoming], 0, datetime.now(timezone.utc))
    verdicts = DuplicateDetector().detect_two_pass([incoming], index)
    digest = incoming['properties']['geojson_hash']
    assert verdicts[digest].kind == VerdictKind.HASH
    assert verdicts[digest].scope == VerdictScope.LIBRARY


@pytest.mark.django_db
def test_replacement_drafts_excluded_from_sibling_index():
    user = User.objects.create_user(username='dup-repl', email='dup-repl@example.com', password='x')
    feature = _point('replacement', lon=1.0, lat=2.0)
    replacement_queue = ImportQueue.objects.create(
        user=user,
        original_filename='replace.kml',
        raw_file='<kml></kml>',
        replacement=99,
        queue_status=ImportQueue.STATUS_READY,
        imported=False,
    )
    ImportDraftFeature.objects.create(
        queue=replacement_queue,
        user=user,
        geojson=feature,
        geojson_hash=feature['properties']['geojson_hash'],
        name='replacement',
        geometry_type='Point',
        spatial_index=0,
    )
    index = build_draft_hash_index(user.id, exclude_queue_id=0, uploaded_at=datetime.now(timezone.utc))
    assert index.lookup(feature['properties']['geojson_hash']) is None
