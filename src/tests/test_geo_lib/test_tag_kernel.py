from django.contrib.auth import get_user_model
from django.test import TestCase

from api.models import Collection, CollectionFeatureMembership, CollectionTagRule, FeatureTag
from api.sharing.models import ShareLink
from test_utils.share_fixtures import create_tag_share
from api.services.feature_service import FeatureService, FeatureValidationError
from geo_lib.collections.editor import CollectionEditor, CollectionEditorError
from geo_lib.collections.membership import CollectionMembership
from geo_lib.tags.protected import is_protected_tag
from geo_lib.tags.tag_query import TagQuery, TagQueryError
from geo_lib.tags.tag_set import TagSet
from geo_lib.tags.tag_writer import ProvenancePreserve, SystemTagWriter, TagWriter


def _point(name: str, tags=None, system_tags=None) -> dict:
    return {
        'type': 'Feature',
        'geometry': {'type': 'Point', 'coordinates': [10.0, 20.0, 0.0]},
        'properties': {
            'name': name,
            'tags': tags or [],
            'system_tags': system_tags or [],
        },
    }


class TestTagSetAndProtected(TestCase):
    def test_normalize_lowercases_then_rejects_protected(self):
        with self.assertRaises(Exception):
            TagSet.normalize_user_tags(['TYPE:point'])

    def test_validate_user_tags_rejects_protected_after_lowercase(self):
        with self.assertRaises(FeatureValidationError):
            FeatureService.validate_user_tags(['TYPE:point'])

    def test_normalize_dedupes_and_nfc(self):
        tags = TagSet.normalize_user_tags([' Trail ', 'trail', 'RIDGE'])
        self.assertEqual(tags, ('trail', 'ridge'))

    def test_is_protected_after_lowercase(self):
        self.assertTrue(is_protected_tag('TYPE:point'))
        self.assertTrue(is_protected_tag('Quick-Point'))
        self.assertFalse(is_protected_tag('hiking'))

    def test_from_properties_and_matches(self):
        tag_set = TagSet.from_properties({
            'tags': ['hiking'],
            'system_tags': ['ski-resort:test'],
        })
        exact = TagQuery.parse(['hiking'], match_mode='AND', prefix=True)
        self.assertTrue(exact.matches(tag_set))
        prefix = TagQuery.parse(['ski-resort:'], match_mode='OR', prefix=True)
        self.assertTrue(prefix.matches(tag_set))
        missing = TagQuery.parse(['camping'], match_mode='AND', prefix=True)
        self.assertFalse(missing.matches(tag_set))

    def test_invalid_match_mode(self):
        with self.assertRaises(TagQueryError):
            TagQuery.parse(['hiking'], match_mode='XOR')

    def test_parse_search_matches_substring_on_feature_tag(self):
        tag_set = TagSet.from_properties({
            'tags': ['user-tag-1'],
            'system_tags': ['system-tag-2'],
        })
        query = TagQuery.parse_search('user-tag')
        self.assertTrue(query.contains)
        self.assertTrue(query.matches(tag_set))
        self.assertTrue(TagQuery.parse_search('system-tag').matches(tag_set))
        self.assertFalse(TagQuery.parse_search('camping').matches(tag_set))
        predicate, params = query.to_sql_predicate(feature_id_sql='api_featurestore.id')
        self.assertIn('ILIKE', predicate)
        self.assertIn('%user-tag%', params)


class TestTagWriter(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='tags@example.com',
            password='testpass123',
            username='taguser',
        )

    def test_set_user_tags_indexes_feature(self):
        feature = FeatureService.create(self.user, _point('A', tags=['old']))
        TagWriter.set_user_tags(feature, ['New Trail'])
        feature.refresh_from_db()
        self.assertEqual(feature.geojson['properties']['tags'], ['new trail'])
        self.assertTrue(
            FeatureTag.objects.filter(feature=feature, tag_key='new trail', namespace='user').exists()
        )
        self.assertFalse(
            FeatureTag.objects.filter(feature=feature, tag_key='old').exists()
        )

    def test_rename_updates_features_share_and_rules(self):
        feature = FeatureService.create(self.user, _point('A', tags=['alpha']))
        create_tag_share(self.user, 'alpha', token='share-alpha')
        collection = Collection.objects.create(user=self.user, name='C')
        CollectionTagRule.objects.create(collection=collection, tag='alpha')

        updated = TagWriter.rename_user_tag(self.user, 'alpha', 'beta')
        self.assertEqual(updated, 1)
        feature.refresh_from_db()
        self.assertEqual(feature.geojson['properties']['tags'], ['beta'])
        self.assertEqual(ShareLink.objects.get(token='share-alpha').subject_ref, 'beta')
        self.assertEqual(CollectionTagRule.objects.get(collection=collection).tag, 'beta')

    def test_remove_user_tag_leaves_siblings(self):
        feature = FeatureService.create(self.user, _point('A', tags=['keep', 'drop']))
        TagWriter.remove_user_tag(self.user, 'drop', feature=feature)
        feature.refresh_from_db()
        self.assertEqual(feature.geojson['properties']['tags'], ['keep'])

    def test_regenerate_preserves_provenance(self):
        feature = FeatureService.create(
            self.user,
            _point(
                'A',
                tags=['user-keep'],
                system_tags=['quick-point', 'import-year:2020', 'source-file:demo.gpx', 'type:point'],
            ),
        )
        generated = ['type:point', 'import-year:2099', 'elevation:0-500']
        SystemTagWriter.regenerate(feature, generated, ProvenancePreserve())
        feature.refresh_from_db()
        system = feature.geojson['properties']['system_tags']
        self.assertIn('quick-point', system)
        self.assertIn('import-year:2020', system)
        self.assertIn('source-file:demo.gpx', system)
        self.assertNotIn('import-year:2099', system)
        self.assertIn('elevation:0-500', system)
        self.assertEqual(feature.geojson['properties']['tags'], ['user-keep'])


class TestCollectionKernel(TestCase):
    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email='cols@example.com',
            password='testpass123',
            username='coluser',
        )
        self.other = User.objects.create_user(
            email='other@example.com',
            password='testpass123',
            username='otheruser',
        )

    def test_resolve_is_main_map_or_of_rules_and_pins(self):
        tagged = FeatureService.create(self.user, _point('Tagged', tags=['lake']))
        pinned = FeatureService.create(self.user, _point('Pinned', tags=['other']))
        FeatureService.create(self.user, _point('Places', tags=['lake']), scope='places')
        FeatureService.create(self.other, _point('Foreign', tags=['lake']))

        collection = Collection.objects.create(user=self.user, name='Lakes')
        CollectionTagRule.objects.create(collection=collection, tag='lake')
        CollectionFeatureMembership.objects.create(collection=collection, feature=pinned)

        ids = CollectionMembership.feature_ids(collection)
        self.assertEqual(ids, {tagged.id, pinned.id})

    def test_editor_rejects_prefix_rules_and_foreign_pins(self):
        FeatureService.create(self.user, _point('A', tags=['lake']))
        foreign = FeatureService.create(self.other, _point('B', tags=['lake']))
        with self.assertRaises(CollectionEditorError):
            CollectionEditor.validate_rules(self.user, ['lake:'])
        pins = CollectionEditor.validate_pins(self.user, [foreign.id])
        self.assertEqual(pins, [])
        rules = CollectionEditor.validate_rules(self.user, ['lake', 'missing'])
        self.assertEqual(rules, ['lake'])
