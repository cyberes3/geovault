"""
Create or update the OAuth2 Application used by the GeoVault Android apps (places & uploader).

Run after migrations so the Android apps can use the authorization code + PKCE flow.
Idempotent: if an application with client_id=geovault-android exists, it is updated if needed.
"""
from django.core.management.base import BaseCommand
from django.contrib.auth import get_user_model
from oauth2_provider.models import Application

User = get_user_model()

CLIENT_ID = "geovault-android"
REDIRECT_URIS = (
    "com.geovault.places://oauth/callback "
    "com.geovault.places.debug://oauth/callback "
    "com.geovault.uploader://oauth/callback "
    "com.geovault.uploader.debug://oauth/callback"
)


class Command(BaseCommand):
    help = "Create or update the OAuth2 application for GeoVault Android apps (client_id=geovault-android)."

    def handle(self, *args, **options):
        app = Application.objects.filter(client_id=CLIENT_ID).first()
        if app:
            updated = []
            if app.redirect_uris.strip() != REDIRECT_URIS.strip():
                app.redirect_uris = REDIRECT_URIS
                updated.append("redirect_uris")
            if app.skip_authorization:
                app.skip_authorization = False
                updated.append("skip_authorization")
            if updated:
                app.save(update_fields=updated)
                self.stdout.write(
                    self.style.SUCCESS(f"Updated {', '.join(updated)} for OAuth2 application '{CLIENT_ID}'.")
                )
            else:
                self.stdout.write(f"OAuth2 application '{CLIENT_ID}' already exists and is up to date.")
            return

        user = User.objects.filter(is_superuser=True).order_by("pk").first()
        if not user:
            user = User.objects.order_by("pk").first()
        if not user:
            self.stdout.write(
                self.style.ERROR("No user found. Create a user (e.g. run migrations and create a superuser) first.")
            )
            return

        Application.objects.create(
            name="GeoVault Android",
            user=user,
            client_id=CLIENT_ID,
            client_type=Application.CLIENT_PUBLIC,
            authorization_grant_type=Application.GRANT_AUTHORIZATION_CODE,
            redirect_uris=REDIRECT_URIS,
            skip_authorization=False,
        )
        self.stdout.write(
            self.style.SUCCESS(f"Created OAuth2 application '{CLIENT_ID}' with redirect URIs for places and uploader.")
        )
