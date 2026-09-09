"""Collection CRUD and bulk ops. Views stay thin."""

from django.db import transaction
from django.http import Http404

from api.models import Collection, CollectionFeatureMembership, CollectionTagRule
from api.services.feature_service import FeatureService
from geo_lib.collections.definition import CollectionDefinition, CollectionEditorInput
from geo_lib.collections.editor import CollectionEditor
from geo_lib.collections.membership import CollectionMembership
from geo_lib.contracts.envelope import ListPage
from geo_lib.contracts.pagination import PageQuery


class CollectionService:
    @staticmethod
    def get_owned_or_404(user, collection_id) -> Collection:
        try:
            return Collection.objects.get(id=collection_id, user=user)
        except Collection.DoesNotExist:
            raise Http404("Collection not found")

    @staticmethod
    def serialize(collection: Collection, feature_count: int | None = None) -> dict:
        tags = list(collection.tag_rules.order_by('id').values_list('tag', flat=True))
        feature_ids = list(collection.feature_pins.order_by('id').values_list('feature_id', flat=True))
        if feature_count is None:
            feature_count = CollectionMembership.count(collection)
        return CollectionDefinition(
            id=collection.id,
            user_id=collection.user_id,
            name=collection.name,
            description=collection.description,
            tags=tuple(tags),
            feature_ids=tuple(feature_ids),
            feature_count=feature_count,
            created_at=collection.created_at.isoformat(),
            updated_at=collection.updated_at.isoformat(),
        ).as_dict()

    @staticmethod
    def list_owned(user, page_query: PageQuery) -> ListPage:
        qs = Collection.objects.filter(user=user).order_by('-created_at')
        total = qs.count()
        start = (page_query.page - 1) * page_query.page_size
        collections = list(qs[start:start + page_query.page_size])
        counts = CollectionMembership.counts_for(collections)
        items = [
            CollectionService.serialize(collection, feature_count=counts[collection.id])
            for collection in collections
        ]
        return ListPage.of(items, page_query.page, page_query.page_size, total)

    @staticmethod
    def create(user, editor: CollectionEditorInput) -> Collection:
        name = (editor.name or '').strip()
        description = editor.description.strip() if editor.description else None
        tags = CollectionEditor.validate_rules(user, editor.tags)
        feature_ids = CollectionEditor.validate_pins(user, editor.feature_ids)
        with transaction.atomic():
            collection = Collection.objects.create(
                user=user,
                name=name,
                description=description,
            )
            CollectionService._replace_rules(collection, tags)
            CollectionService._replace_pins(collection, feature_ids)
        return collection

    @staticmethod
    def update(collection: Collection, editor: CollectionEditorInput) -> Collection:
        with transaction.atomic():
            if editor.name is not None:
                name = editor.name.strip()
                if name:
                    collection.name = name
            if editor.description_provided:
                description = editor.description
                collection.description = description.strip() if description else None
            if editor.tags is not None:
                CollectionService._replace_rules(
                    collection,
                    CollectionEditor.validate_rules(collection.user, editor.tags),
                )
            if editor.feature_ids is not None:
                CollectionService._replace_pins(
                    collection,
                    CollectionEditor.validate_pins(collection.user, editor.feature_ids),
                )
            collection.save()
        return collection

    @staticmethod
    def delete(collection: Collection) -> None:
        collection.delete()

    @staticmethod
    def apply_bulk_operations(collection: Collection, bulk_ops: dict) -> int:
        qs = CollectionMembership.resolve(collection).only('id', 'geojson')
        return FeatureService.apply_bulk_operations(qs, bulk_ops)

    @staticmethod
    def _replace_rules(collection: Collection, tags: list[str]) -> None:
        CollectionTagRule.objects.filter(collection=collection).delete()
        CollectionTagRule.objects.bulk_create([
            CollectionTagRule(collection=collection, tag=tag) for tag in tags
        ])

    @staticmethod
    def _replace_pins(collection: Collection, feature_ids: list[int]) -> None:
        CollectionFeatureMembership.objects.filter(collection=collection).delete()
        CollectionFeatureMembership.objects.bulk_create([
            CollectionFeatureMembership(collection=collection, feature_id=feature_id)
            for feature_id in feature_ids
        ])
