"""SkipIntent apply rules: geometry Restore imports; hash stays blocked."""

from django.test import SimpleTestCase

from geo_lib.duplicates.skip_intent import SkipIntent
from geo_lib.duplicates.verdict import DuplicateVerdict, VerdictKind, VerdictScope


def _verdict(kind: VerdictKind) -> DuplicateVerdict:
    scope = VerdictScope.LIBRARY if kind != VerdictKind.NONE else VerdictScope.NONE
    return DuplicateVerdict(kind=kind, scope=scope)


class TestSkipIntentRestore(SimpleTestCase):
    def test_geometry_restore_imports(self):
        intent = SkipIntent(
            auto_skipped_geometry={'geom-hash'},
            user_restored_geometry={'geom-hash'},
        )
        self.assertFalse(intent.should_skip('geom-hash', _verdict(VerdictKind.GEOMETRY)))

    def test_geometry_without_restore_is_skipped(self):
        intent = SkipIntent(auto_skipped_geometry={'geom-hash'})
        self.assertTrue(intent.should_skip('geom-hash', _verdict(VerdictKind.GEOMETRY)))

    def test_hash_duplicate_is_never_restorable(self):
        intent = SkipIntent(
            blocked={'hash-id'},
            user_restored_geometry={'hash-id'},
        )
        self.assertTrue(intent.should_skip('hash-id', _verdict(VerdictKind.HASH)))

    def test_from_verdicts_keeps_prior_restore(self):
        prior = SkipIntent(user_restored_geometry={'geom-hash'})
        verdicts = {'geom-hash': _verdict(VerdictKind.GEOMETRY)}
        intent = SkipIntent.from_verdicts(verdicts, prior)
        self.assertIn('geom-hash', intent.user_restored_geometry)
        self.assertNotIn('geom-hash', intent.auto_skipped_geometry)
        self.assertFalse(intent.should_skip('geom-hash', verdicts['geom-hash']))

    def test_from_verdicts_does_not_put_hash_in_restorable_skip(self):
        verdicts = {'hash-id': _verdict(VerdictKind.HASH)}
        intent = SkipIntent.from_verdicts(verdicts)
        self.assertIn('hash-id', intent.blocked)
        self.assertNotIn('hash-id', intent.auto_skipped_geometry)
        self.assertNotIn('hash-id', intent.to_wire()['skipped'])
