from django.core.management.base import BaseCommand, CommandError
from django.db import transaction
from api.models import ImportQueue, FeatureStore, DatabaseLogging


class Command(BaseCommand):
    help = 'Reset development environment by clearing import queue, featurestore, and logs'

    def add_arguments(self, parser):
        parser.add_argument(
            '--force',
            action='store_true',
            help='Force reset without confirmation prompt',
        )
        parser.add_argument(
            '--dry-run',
            action='store_true',
            help='Show what would be deleted without actually deleting',
        )
        parser.add_argument(
            '--components',
            nargs='+',
            choices=['import_queue', 'featurestore', 'logs'],
            default=['import_queue', 'featurestore', 'logs'],
            help='Specify which components to reset (default: all)',
        )

    def handle(self, *args, **options):
        force = options['force']
        dry_run = options['dry_run']
        components = options['components']

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN MODE - No changes will be made'))

        # Show what will be reset
        self.stdout.write(self.style.WARNING('=== DEVELOPMENT ENVIRONMENT RESET ==='))
        self.stdout.write(f'Components to reset: {", ".join(components)}')
        
        if not force and not dry_run:
            confirm = input('Are you sure you want to reset the development environment? This will delete ALL data in the selected components. Type "yes" to continue: ')
            if confirm.lower() != 'yes':
                self.stdout.write(self.style.WARNING('Reset cancelled'))
                return

        with transaction.atomic():
            total_deleted = 0
            
            # Reset Import Queue
            if 'import_queue' in components:
                import_count = ImportQueue.objects.count()
                if import_count > 0:
                    if dry_run:
                        self.stdout.write(f'Would delete {import_count} items from import queue')
                    else:
                        deleted_count, _ = ImportQueue.objects.all().delete()
                        self.stdout.write(
                            self.style.SUCCESS(
                                f'Successfully deleted {deleted_count} items from import queue'
                            )
                        )
                        total_deleted += deleted_count
                else:
                    self.stdout.write('Import queue is already empty')

            # Reset FeatureStore
            if 'featurestore' in components:
                feature_count = FeatureStore.objects.count()
                if feature_count > 0:
                    if dry_run:
                        self.stdout.write(f'Would delete {feature_count} items from featurestore')
                    else:
                        deleted_count, _ = FeatureStore.objects.all().delete()
                        self.stdout.write(
                            self.style.SUCCESS(
                                f'Successfully deleted {deleted_count} items from featurestore'
                            )
                        )
                        total_deleted += deleted_count
                else:
                    self.stdout.write('Featurestore is already empty')

            # Reset Logs
            if 'logs' in components:
                log_count = DatabaseLogging.objects.count()
                if log_count > 0:
                    if dry_run:
                        self.stdout.write(f'Would delete {log_count} log entries')
                    else:
                        deleted_count, _ = DatabaseLogging.objects.all().delete()
                        self.stdout.write(
                            self.style.SUCCESS(
                                f'Successfully deleted {deleted_count} log entries'
                            )
                        )
                        total_deleted += deleted_count
                else:
                    self.stdout.write('Logs are already empty')

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN COMPLETE - No changes were made'))
        else:
            self.stdout.write(
                self.style.SUCCESS(
                    f'=== RESET COMPLETE ===\n'
                    f'Total items deleted: {total_deleted}\n'
                    f'Development environment has been reset successfully!'
                )
            )
