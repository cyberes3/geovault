"""Example ImportProvider: extract a name, write a scoped ExampleItem on finalize."""
import logging

from django.contrib.auth import get_user_model

from extensions.example_extension.src.backend.models import ExampleItem
from website.extensions.import_provider import ExternalIds, ImportHookPayload

logger = logging.getLogger("example_extension.import_provider")


class ExampleImportProvider:
    def extract_external_ids(self, geojson: dict) -> ExternalIds:
        properties = geojson.get("properties") if isinstance(geojson, dict) else None
        if not isinstance(properties, dict):
            return ExternalIds()
        name = properties.get("name")
        if not name:
            return ExternalIds()
        return ExternalIds(by_provider={"example_extension": {"name": str(name)}})

    def on_import_finalized(self, payload: ImportHookPayload) -> None:
        feature_count = len(payload.created_features)
        if feature_count == 0:
            return
        User = get_user_model()
        try:
            user = User.objects.get(id=payload.user_id)
        except User.DoesNotExist:
            logger.error("Example ImportProvider: user %s does not exist", payload.user_id)
            return
        ExampleItem.objects.create(
            user=user,
            name=f"Import {payload.import_item.id}",
            description=f"{feature_count} features imported",
        )
        logger.info(
            "Example ImportProvider: recorded import %s for user %s (%s features)",
            payload.import_item.id,
            payload.user_id,
            feature_count,
        )
