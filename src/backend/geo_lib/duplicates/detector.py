from typing import Any

from geo_lib.duplicates.fingerprint import GeometryFingerprint
from geo_lib.duplicates.identity import GeoJsonHash
from geo_lib.duplicates.index import DuplicateIndex
from geo_lib.duplicates.verdict import DuplicateVerdict, VerdictKind, VerdictScope
from geo_lib.feature_id import generate_geojson_hash


class DuplicateDetector:
    def dedupe_internal(self, features: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], int]:
        if not features:
            return features, 0
        seen: set[str] = set()
        unique: list[dict[str, Any]] = []
        dropped = 0
        for feature in features:
            digest = generate_geojson_hash(feature)
            if digest in seen:
                dropped += 1
                continue
            seen.add(digest)
            unique.append(feature)
        return unique, dropped

    def detect_two_pass(
        self,
        features: list[dict[str, Any]],
        index: DuplicateIndex,
    ) -> dict[str, DuplicateVerdict]:
        verdicts: dict[str, DuplicateVerdict] = {}
        remaining: list[tuple[str, dict[str, Any], int]] = []
        for input_id, feature in enumerate(features):
            digest = GeoJsonHash.of(feature).value
            library_hash = index.library_hashes.lookup(digest)
            if library_hash is not None:
                verdicts[digest] = DuplicateVerdict(
                    kind=VerdictKind.HASH,
                    scope=VerdictScope.LIBRARY,
                    matches=(library_hash,),
                )
                continue
            remaining.append((digest, feature, input_id))

        after_library_geom: list[tuple[str, dict[str, Any], int]] = []
        for digest, feature, input_id in remaining:
            fingerprint = GeometryFingerprint.of(feature)
            library_geom = index.library_geometry.matches_for(input_id, fingerprint)
            if library_geom:
                verdicts[digest] = DuplicateVerdict(
                    kind=VerdictKind.GEOMETRY,
                    scope=VerdictScope.LIBRARY,
                    matches=tuple(library_geom),
                )
                continue
            after_library_geom.append((digest, feature, input_id))

        after_draft_hash: list[tuple[str, dict[str, Any], int]] = []
        for digest, feature, input_id in after_library_geom:
            draft_hash = index.draft_hashes.lookup(digest)
            if draft_hash is not None:
                verdicts[digest] = DuplicateVerdict(
                    kind=VerdictKind.HASH,
                    scope=VerdictScope.DRAFT_QUEUE,
                    matches=(draft_hash,),
                )
                continue
            after_draft_hash.append((digest, feature, input_id))

        for digest, feature, input_id in after_draft_hash:
            fingerprint = GeometryFingerprint.of(feature)
            draft_geom = index.draft_geometry.matches_for(input_id, fingerprint)
            if draft_geom:
                verdicts[digest] = DuplicateVerdict(
                    kind=VerdictKind.GEOMETRY,
                    scope=VerdictScope.DRAFT_QUEUE,
                    matches=tuple(draft_geom),
                )
            else:
                verdicts[digest] = DuplicateVerdict.none()
        return verdicts
