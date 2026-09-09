"""The only mutator for FeatureTag and properties.tags / system_tags."""

from __future__ import annotations

from dataclasses import dataclass

from django.db import transaction

from api.models import CollectionTagRule, FeatureStore, FeatureTag
from api.sharing.models import ShareLink
from geo_lib.reverse_geocoding.constants import REVERSE_GEOCODING_TAG_PREFIXES
from geo_lib.sharing.constants import KIND_TAG
from geo_lib.sharing.subject_ref import canonicalize_subject_ref
from geo_lib.tags.protected import TagValidationError, is_protected_tag
from geo_lib.tags.scope import NAMESPACE_SYSTEM, NAMESPACE_USER, index_scope
from geo_lib.tags.tag_query import TagQuery
from geo_lib.tags.tag_set import TagSet, normalize_tag_text

GEOCODING_REPLACE_PREFIXES = tuple(REVERSE_GEOCODING_TAG_PREFIXES) + (
    'geo-city',
    'geo-state',
    'geo-country',
)

PROVENANCE_PREFIXES = (
    'import-year',
    'import-month',
    'source-file',
    'quick-point',
)


@dataclass(frozen=True)
class ProvenancePreserve:
    prefixes: tuple[str, ...] = PROVENANCE_PREFIXES


def _single_user_tag(tag: str) -> str:
    normalized = TagSet.normalize_user_tags([tag])
    if len(normalized) != 1:
        raise TagValidationError('Tag name cannot be empty')
    return normalized[0]


def _properties(feature: FeatureStore) -> dict:
    geojson = feature.geojson
    if not isinstance(geojson, dict):
        feature.geojson = {'type': 'Feature', 'properties': {}, 'geometry': None}
        geojson = feature.geojson
    properties = geojson.get('properties')
    if not isinstance(properties, dict):
        properties = {}
        geojson['properties'] = properties
    return properties


def _replace_namespace(feature: FeatureStore, namespace: str, tags: tuple[str, ...]) -> None:
    FeatureTag.objects.filter(feature=feature, namespace=namespace).delete()
    if not tags:
        return
    FeatureTag.objects.bulk_create(
        [
            FeatureTag(
                user_id=feature.user_id,
                feature=feature,
                tag_key=tag,
                namespace=namespace,
                scope=index_scope(feature.scope),
            )
            for tag in tags
        ]
    )


def _persist_tag_set(feature: FeatureStore, tag_set: TagSet, *, save_feature: bool = True) -> None:
    tag_set.apply_to_properties(_properties(feature))
    if save_feature:
        feature.save(update_fields=['geojson'])
    _replace_namespace(feature, NAMESPACE_USER, tag_set.user)
    _replace_namespace(feature, NAMESPACE_SYSTEM, tag_set.system)


class TagWriter:
    @staticmethod
    def reindex_feature(feature: FeatureStore) -> None:
        current = TagSet.from_properties(_properties(feature))
        raw_user = [tag for tag in current.user if tag.strip()]
        normalized = TagSet(
            user=TagSet.normalize_user_tags(raw_user, reject_protected=False, drop_protected=False),
            system=TagSet.normalize_system_tags(current.system),
        )
        _persist_tag_set(feature, normalized)

    @staticmethod
    def reindex_many(features: list[FeatureStore]) -> None:
        for feature in features:
            if feature.id:
                TagWriter.reindex_feature(feature)

    @staticmethod
    def set_user_tags(feature: FeatureStore, tags) -> FeatureStore:
        normalized = TagSet.normalize_user_tags(tags)
        current = TagSet.from_properties(_properties(feature))
        _persist_tag_set(feature, current.with_user(normalized))
        return feature

    @staticmethod
    def merge_user_tags(feature: FeatureStore, extra_tags) -> FeatureStore:
        current = TagSet.from_properties(_properties(feature))
        merged = TagSet.normalize_user_tags([*current.user, *list(extra_tags or [])])
        return TagWriter.set_user_tags(feature, list(merged))

    @staticmethod
    def rename_user_tag(user, old_name: str, new_name: str) -> int:
        old_norm = _single_user_tag(old_name)
        new_norm = _single_user_tag(new_name)
        if old_norm == new_norm:
            return 0

        with transaction.atomic():
            rows = list(
                FeatureTag.objects.filter(
                    user=user,
                    tag_key=old_norm,
                    namespace=NAMESPACE_USER,
                ).select_related('feature')
            )
            seen_features: set[int] = set()
            for row in rows:
                feature = row.feature
                if feature.id in seen_features:
                    continue
                seen_features.add(feature.id)
                current = TagSet.from_properties(_properties(feature))
                replaced = [new_norm if tag == old_norm else tag for tag in current.user]
                TagWriter.set_user_tags(feature, replaced)

            old_refs = {old_norm, canonicalize_subject_ref(KIND_TAG, old_name)}
            ShareLink.objects.filter(
                owner=user,
                subject_kind=KIND_TAG,
                subject_ref__in=list(old_refs),
            ).update(subject_ref=new_norm)

            for rule in CollectionTagRule.objects.filter(collection__user=user, tag=old_norm):
                if CollectionTagRule.objects.filter(collection=rule.collection, tag=new_norm).exists():
                    rule.delete()
                else:
                    rule.tag = new_norm
                    rule.save(update_fields=['tag'])

        return len(seen_features)

    @staticmethod
    def remove_user_tag(user, tag: str, *, feature: FeatureStore | None = None) -> int:
        norm = _single_user_tag(tag)
        qs = FeatureTag.objects.filter(user=user, tag_key=norm, namespace=NAMESPACE_USER)
        if feature is not None:
            qs = qs.filter(feature=feature)
        features = list(FeatureStore.objects.filter(id__in=qs.values_list('feature_id', flat=True)))
        for stored in features:
            current = TagSet.from_properties(_properties(stored))
            TagWriter.set_user_tags(stored, [item for item in current.user if item != norm])
        return len(features)

    @staticmethod
    def delete_features_with_tag(user, tag: str) -> int:
        query = TagQuery.parse([normalize_tag_text(tag)], match_mode='OR', prefix=False, scope=None)
        qs = FeatureStore.objects.owned_by(user).main_map().filter(query.to_django_q(user.id))
        count = qs.count()
        qs.delete()
        return count


class SystemTagWriter:
    @staticmethod
    def write_system_tags(feature: FeatureStore, system_tags: list[str]) -> FeatureStore:
        current = TagSet.from_properties(_properties(feature))
        normalized = TagSet.normalize_system_tags(system_tags)
        _persist_tag_set(feature, current.with_system(normalized))
        return feature

    @staticmethod
    def write_import_tags(feature: FeatureStore, system_tags: list[str]) -> FeatureStore:
        return SystemTagWriter.write_system_tags(feature, system_tags)

    @staticmethod
    def write_creation_tags(feature: FeatureStore, system_tags: list[str], extra: list[str] | None = None) -> FeatureStore:
        combined = list(system_tags)
        if extra:
            combined.extend(extra)
        return SystemTagWriter.write_system_tags(feature, combined)

    @staticmethod
    def replace_geocoding_tags(feature: FeatureStore, new_geocode_tags: list[str]) -> FeatureStore:
        current = TagSet.from_properties(_properties(feature))
        kept = [
            tag for tag in current.system
            if not is_protected_tag(tag, GEOCODING_REPLACE_PREFIXES)
        ]
        replacement = TagSet.normalize_system_tags([*kept, *new_geocode_tags])
        return SystemTagWriter.write_system_tags(feature, list(replacement))

    @staticmethod
    def apply_regenerated(
        feature: FeatureStore,
        generated: list[str],
        preserve: ProvenancePreserve | None = None,
    ) -> list[str]:
        """Keep provenance tags and replace the rest with `generated`."""
        preserve = preserve or ProvenancePreserve()
        current = TagSet.from_properties(_properties(feature))
        preserved = [
            tag for tag in current.system
            if is_protected_tag(tag, preserve.prefixes)
        ]
        generated_kept = [
            tag for tag in generated
            if not is_protected_tag(tag, preserve.prefixes)
        ]
        return list(TagSet.normalize_system_tags([*generated_kept, *preserved]))

    @staticmethod
    def regenerate(feature: FeatureStore, generated: list[str], preserve: ProvenancePreserve | None = None) -> FeatureStore:
        new_system = SystemTagWriter.apply_regenerated(feature, generated, preserve)
        return SystemTagWriter.write_system_tags(feature, new_system)
