"""CalTopo ImportProvider: stash map/feature ids before whitelist, map them after import."""
import logging

from django.contrib.auth import get_user_model

from extensions.caltopo.src.backend.models import CalTopoUser
from website.extensions.import_provider import ExternalIds, ImportHookPayload

logger = logging.getLogger("caltopo.import_provider")


class CaltopoImportProvider:
    def extract_external_ids(self, geojson: dict) -> ExternalIds:
        properties = geojson.get("properties") if isinstance(geojson, dict) else None
        if not isinstance(properties, dict):
            return ExternalIds()
        map_id = properties.get("caltopo_map_id")
        feature_id = properties.get("caltopo_feature_id")
        if not map_id or not feature_id:
            return ExternalIds()
        return ExternalIds(
            by_provider={
                "caltopo": {
                    "map_id": str(map_id),
                    "feature_id": str(feature_id),
                }
            }
        )

    def on_import_finalized(self, payload: ImportHookPayload) -> None:
        if not payload.created_features:
            return

        User = get_user_model()
        try:
            user = User.objects.get(id=payload.user_id)
        except User.DoesNotExist:
            return

        caltopo_user, _ = CalTopoUser.objects.get_or_create(user=user)
        if not caltopo_user.imported_features:
            caltopo_user.imported_features = {}

        updated = False
        for feature, external_ids in zip(payload.created_features, payload.external_ids):
            caltopo_ids = external_ids.by_provider.get("caltopo") or {}
            map_id = caltopo_ids.get("map_id")
            feature_id = caltopo_ids.get("feature_id")
            if not map_id or not feature_id:
                continue
            caltopo_user.imported_features.setdefault(map_id, {})[feature_id] = feature.id
            updated = True

        if updated:
            caltopo_user.save()
            logger.info(
                "CalTopo ImportProvider: updated imported_features for user %s",
                payload.user_id,
            )
