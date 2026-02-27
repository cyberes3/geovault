"""
Revoke all OAuth2 access tokens so clients must use refresh_token to get a new access token.

Useful for testing the Android app's token refresh flow: run this, then use the app;
the next API request will get 401 and the app should refresh and retry.

Example:
  python manage.py revoke_oauth_access_tokens
"""
from django.core.management.base import BaseCommand
from oauth2_provider.models import get_access_token_model


class Command(BaseCommand):
    help = "Revoke (delete) all OAuth2 access tokens so clients must refresh. Dev use."

    def add_arguments(self, parser):
        parser.add_argument(
            "--dry-run",
            action="store_true",
            help="Show how many tokens would be revoked without deleting.",
        )

    def handle(self, *args, **options):
        AccessToken = get_access_token_model()
        qs = AccessToken.objects.all()
        count = qs.count()
        if count == 0:
            self.stdout.write("No access tokens to revoke.")
            return

        if options.get("dry_run"):
            self.stdout.write(self.style.WARNING(f"Dry run: would revoke {count} access token(s)."))
            return

        qs.delete()
        self.stdout.write(self.style.SUCCESS(f"Revoked {count} access token(s). Next API call will get 401 until client refreshes."))
