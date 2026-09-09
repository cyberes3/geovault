from api.sharing.models import ShareGrant
from geo_lib.sharing.constants import TRACKER_KINDS
from geo_lib.sharing.errors import ShareError


class ShareGrantService:
    @staticmethod
    def has_grant(resource_kind: str, resource_id, user) -> bool:
        if user is None or not getattr(user, "is_authenticated", False):
            return False
        return ShareGrant.objects.for_resource(resource_kind, str(resource_id)).filter(
            grantee_user=user
        ).exists()

    @staticmethod
    def grantee_ids(resource_kind: str, resource_id) -> set[int]:
        return set(
            ShareGrant.objects.for_resource(resource_kind, str(resource_id)).values_list(
                "grantee_user_id", flat=True
            )
        )

    @staticmethod
    def grantee_emails(resource_kind: str, resource_id) -> list[str]:
        emails = ShareGrant.objects.for_resource(resource_kind, str(resource_id)).values_list(
            "grantee_user__email", flat=True
        )
        return [email for email in emails if email]

    @staticmethod
    def resource_ids_for_user(resource_kind: str, user) -> set[str]:
        return set(
            ShareGrant.objects.filter(resource_kind=resource_kind, grantee_user=user).values_list(
                "resource_id", flat=True
            )
        )

    @staticmethod
    def set_grantees(resource_kind: str, resource_id, users) -> tuple[set[int], set[int]]:
        if resource_kind not in TRACKER_KINDS:
            raise ShareError(f"Invalid grant resource_kind: {resource_kind}")
        resource_key = str(resource_id)
        target_ids = {user.id for user in users}
        current_ids = ShareGrantService.grantee_ids(resource_kind, resource_key)
        to_add = target_ids - current_ids
        to_remove = current_ids - target_ids
        for user in users:
            if user.id in to_add:
                ShareGrant.objects.get_or_create(
                    resource_kind=resource_kind,
                    resource_id=resource_key,
                    grantee_user=user,
                )
        if to_remove:
            ShareGrant.objects.for_resource(resource_kind, resource_key).filter(
                grantee_user_id__in=to_remove
            ).delete()
        return to_add, to_remove

    @staticmethod
    def clear(resource_kind: str, resource_id) -> None:
        ShareGrant.objects.for_resource(resource_kind, str(resource_id)).delete()

    @staticmethod
    def add(resource_kind: str, resource_id, user) -> ShareGrant:
        grant, _ = ShareGrant.objects.get_or_create(
            resource_kind=resource_kind,
            resource_id=str(resource_id),
            grantee_user=user,
        )
        return grant

    @staticmethod
    def remove(resource_kind: str, resource_id, user) -> None:
        ShareGrant.objects.for_resource(resource_kind, str(resource_id)).filter(
            grantee_user=user
        ).delete()
