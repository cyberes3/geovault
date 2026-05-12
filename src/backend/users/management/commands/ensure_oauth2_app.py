"""
Create or update the OAuth2 Applications used by the GeoVault Android apps
(places, uploader, tracker, survey, NGS Navigator).

Run after migrations so the Android apps can use the authorization code + PKCE flow.
Idempotent: creates/updates one app per Android app so the Authorized OAuth Applications list
lists each app by name.

Applications are always stored with no owner user (null). Existing rows that still have an owner
are updated to clear it.
"""
from django.core.management.base import BaseCommand
from oauth2_provider.models import Application

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
    {
        "client_id": "geovault-android-ngs",
        "name": "GeoVault Android NGS Navigator",
        "redirect_uris": (
            "com.geovault.ngsnavigator://oauth/callback\n"
            "com.geovault.ngsnavigator.debug://oauth/callback"
        ),
    },
)


def _ensure_app(client_id, name, redirect_uris):
    app = Application.objects.filter(client_id=client_id).first()
    if app:
        updated = []
        if app.user_id is not None:
            app.user = None
            updated.append("user")
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
        user=None,
        client_id=client_id,
        client_type=Application.CLIENT_PUBLIC,
        authorization_grant_type=Application.GRANT_AUTHORIZATION_CODE,
        redirect_uris=redirect_uris,
        skip_authorization=False,
    )
    return True, "created"


class Command(BaseCommand):
    help = "Create or update OAuth2 applications for GeoVault Android (places, uploader, tracker, survey, NGS)."

    def handle(self, *args, **options):
        for spec in ANDROID_APPS:
            changed, msg = _ensure_app(
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
