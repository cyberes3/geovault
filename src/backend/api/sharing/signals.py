from django.db.models.signals import post_delete, post_save
from django.dispatch import receiver

from api.models import Collection, FeatureStore
from api.sharing.models import ShareGrant, ShareLink
from extensions.live_track.src.backend.models import LiveTrack, LiveTrackGroup
from geo_lib.sharing.constants import KIND_COLLECTION
from website.map_share_social.preview_service import invalidate_previews_for_feature, invalidate_previews_for_tokens


@receiver(post_delete, sender=FeatureStore)
def delete_feature_share_rows(sender, instance, **kwargs):
    tokens = list(ShareLink.objects.for_feature(instance.id).values_list("token", flat=True))
    invalidate_previews_for_feature(instance)
    invalidate_previews_for_tokens(tokens)
    ShareLink.objects.for_feature(instance.id).delete()


@receiver(post_save, sender=FeatureStore)
def invalidate_share_previews_on_feature_write(sender, instance, created, update_fields, **kwargs):
    if update_fields is not None and "geometry" not in update_fields and "geojson" not in update_fields:
        return
    invalidate_previews_for_feature(instance)


@receiver(post_delete, sender=Collection)
def delete_collection_share_rows(sender, instance, **kwargs):
    tokens = list(
        ShareLink.objects.for_subject(KIND_COLLECTION, str(instance.id)).values_list("token", flat=True)
    )
    invalidate_previews_for_tokens(tokens)
    ShareLink.objects.for_subject(KIND_COLLECTION, str(instance.id)).delete()


def register_tracker_share_signals():
    @receiver(post_delete, sender=LiveTrack)
    def delete_track_share_rows(sender, instance, **kwargs):
        ShareLink.objects.for_track(instance.id).delete()
        ShareGrant.objects.for_track(instance.id).delete()

    @receiver(post_delete, sender=LiveTrackGroup)
    def delete_group_share_rows(sender, instance, **kwargs):
        ShareLink.objects.for_group(instance.id).delete()
        ShareGrant.objects.for_group(instance.id).delete()
