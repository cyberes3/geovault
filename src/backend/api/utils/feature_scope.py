from django.http import Http404

from api.models import FeatureStore


def require_default_scope_feature(feature: FeatureStore) -> None:
    """Raise Http404 when a scoped feature is accessed via the main map API."""
    if feature.scope is not None:
        raise Http404('Feature not found')
