from dataclasses import dataclass, field

from geo_lib.duplicates.identity import GeoJsonHash
from geo_lib.duplicates.verdict import DuplicateVerdict, VerdictKind


@dataclass
class SkipIntent:
    blocked: set[str] = field(default_factory=set)
    auto_skipped_geometry: set[str] = field(default_factory=set)
    user_skipped: set[str] = field(default_factory=set)
    user_restored_geometry: set[str] = field(default_factory=set)

    def should_skip(self, geojson_hash: GeoJsonHash | str, verdict: DuplicateVerdict) -> bool:
        hash_value = geojson_hash.value if isinstance(geojson_hash, GeoJsonHash) else geojson_hash
        if verdict.kind == VerdictKind.HASH or hash_value in self.blocked:
            return True
        if hash_value in self.user_skipped:
            return True
        if verdict.kind == VerdictKind.GEOMETRY:
            if hash_value in self.user_restored_geometry:
                return False
            if hash_value in self.auto_skipped_geometry:
                return True
        return False

    @classmethod
    def from_verdicts(
        cls,
        verdicts: dict[str, DuplicateVerdict],
        prior: "SkipIntent | None" = None,
    ) -> "SkipIntent":
        prior_restored = set(prior.user_restored_geometry) if prior else set()
        prior_user_skipped = set(prior.user_skipped) if prior else set()
        blocked: set[str] = set()
        auto_skipped: set[str] = set()
        for hash_value, verdict in verdicts.items():
            if verdict.kind == VerdictKind.HASH:
                blocked.add(hash_value)
            elif verdict.kind == VerdictKind.GEOMETRY:
                if hash_value not in prior_restored:
                    auto_skipped.add(hash_value)
        return cls(
            blocked=blocked,
            auto_skipped_geometry=auto_skipped,
            user_skipped=prior_user_skipped - blocked,
            user_restored_geometry=prior_restored,
        )

    @classmethod
    def from_stored(cls, payload: dict | None) -> "SkipIntent":
        data = payload or {}
        return cls(
            blocked=set(data.get('blocked') or []),
            auto_skipped_geometry=set(data.get('auto_skipped_geometry') or []),
            user_skipped=set(data.get('user_skipped') or []),
            user_restored_geometry=set(data.get('user_restored_geometry') or []),
        )

    def to_stored(self) -> dict[str, list[str]]:
        return {
            'blocked': sorted(self.blocked),
            'auto_skipped_geometry': sorted(self.auto_skipped_geometry),
            'user_skipped': sorted(self.user_skipped),
            'user_restored_geometry': sorted(self.user_restored_geometry),
        }

    def to_wire(self) -> dict[str, list[str]]:
        skipped = sorted((self.auto_skipped_geometry | self.user_skipped) - self.user_restored_geometry)
        return {
            'skipped': skipped,
            'restored': sorted(self.user_restored_geometry),
        }
