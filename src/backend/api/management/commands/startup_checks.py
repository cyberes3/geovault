"""
Django management command to run startup checks.
"""

from django.core.management.base import BaseCommand
from website.startup_checks import run_startup_checks


class Command(BaseCommand):
    help = 'Run startup checks for the GeoVault application'

    def handle(self, *args, **options):
        """Run the startup checks."""
        try:
            run_startup_checks()
            self.stdout.write(
                self.style.SUCCESS('All startup checks passed successfully!')
            )
        except SystemExit:
            # The startup checks already printed error messages and exited
            # We just need to re-raise the SystemExit to stop the command
            raise
        except Exception as e:
            self.stdout.write(
                self.style.ERROR(f'Startup checks failed with error: {e}')
            )
            raise