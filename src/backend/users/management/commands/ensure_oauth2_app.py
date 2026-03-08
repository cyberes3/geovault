"""
Create or update the OAuth2 Applications used by the GeoVault Android apps (places, uploader, tracker).

Run after migrations so the Android apps can use the authorization code + PKCE flow.
Idempotent: creates/updates one app per Android app so the Authorized OAuth Applications list
shows "GeoVault Android Places", "GeoVault Android Uploader", "GeoVault Android Tracker".
"""
from django.core.management.base import BaseCommand
from django.contrib.auth import get_user_model
from oauth2_provider.models import Application

User = get_user_model()

# Legacy client_id to remove if present (replaced by geovault-android-places / geovault-android-uploader / geovault-android-tracker)
LEGACY_CLIENT_ID = "geovault-android"

ANDROID_APPS = (
    {
        "client_id": "geovault-android-places",
        "name": "GeoVault Android Places",
        "redirect_uris": (
            "com.geovault.places://oauth/callback\n"
            "com.geovault.places.debug://oauth/callback"
        ),
    },
    {
        "client_id": "geovault-android-uploader",
        "name": "GeoVault Android Uploader",
        "redirect_uris": (
            "com.geovault.uploader://oauth/callback\n"
            "com.geovault.uploader.debug://oauth/callback"
        ),
    },
    {
        "client_id": "geovault-android-tracker",
        "name": "GeoVault Android Tracker",
        "redirect_uris": (
            "com.geovault.tracker://oauth/callback\n"
            "com.geovault.tracker.debug://oauth/callback"
        ),
    },
    {
        "client_id": "geovault-android-survey",
        "name": "GeoVault Android Survey",
        "redirect_uris": (
            "com.geovault.survey://oauth/callback\n"
            "com.geovault.survey.debug://oauth/callback"
        ),
    },
)


def _ensure_app(user, client_id, name, redirect_uris):
    app = Application.objects.filter(client_id=client_id).first()
    if app:
        updated = []
        if app.redirect_uris.strip() != redirect_uris.strip():
            app.redirect_uris = redirect_uris
            updated.append("redirect_uris")
        if app.name != name:
            app.name = name
            updated.append("name")
        if app.skip_authorization:
            app.skip_authorization = False
            updated.append("skip_authorization")
        if updated:
            app.save(update_fields=updated)
            return True, "updated"
        return False, "up to date"
    Application.objects.create(
        name=name,
        user=user,
        client_id=client_id,
        client_type=Application.CLIENT_PUBLIC,
        authorization_grant_type=Application.GRANT_AUTHORIZATION_CODE,
        redirect_uris=redirect_uris,
        skip_authorization=False,
    )
    return True, "created"


class Command(BaseCommand):
    help = "Create or update OAuth2 applications for GeoVault Android (places, uploader, tracker)."

    def handle(self, *args, **options):
        user = User.objects.filter(is_superuser=True).order_by("pk").first()
        if not user:
            user = User.objects.order_by("pk").first()
        if not user:
            self.stdout.write(
                self.style.ERROR("No user found. Create a user (e.g. run migrations and create a superuser) first.")
            )
            return

        # Remove legacy single Android app if present (invalidates its tokens)
        legacy = Application.objects.filter(client_id=LEGACY_CLIENT_ID)
        if legacy.exists():
            legacy.delete()
            self.stdout.write(self.style.WARNING(f"Deleted legacy OAuth2 application '{LEGACY_CLIENT_ID}'."))

        for spec in ANDROID_APPS:
            changed, msg = _ensure_app(
                user,
                spec["client_id"],
                spec["name"],
                spec["redirect_uris"],
            )
            if changed:
                self.stdout.write(
                    self.style.SUCCESS(f"OAuth2 application '{spec['client_id']}' {msg}.")
                )
            else:
                self.stdout.write(f"OAuth2 application '{spec['client_id']}' already {msg}.")
