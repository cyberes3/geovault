from django.core.management.base import BaseCommand, CommandError
from django.db import transaction
from data.models import ImportQueue, FeatureStore, DatabaseLogging


class Command(BaseCommand):
    help = 'Clear all data: import queue, feature store, and database logs. Use with caution!'

    def add_arguments(self, parser):
        parser.add_argument(
            '--dry-run',
            action='store_true',
            help='Show what would be deleted without actually deleting',
        )
        parser.add_argument(
            '--confirm',
            action='store_true',
            help='Skip confirmation prompt (useful for scripts)',
        )
        parser.add_argument(
            '--import-queue-only',
            action='store_true',
            help='Only clear the import queue',
        )
        parser.add_argument(
            '--feature-store-only',
            action='store_true',
            help='Only clear the feature store',
        )
        parser.add_argument(
            '--logs-only',
            action='store_true',
            help='Only clear the database logs',
        )

    def handle(self, *args, **options):
        dry_run = options['dry_run']
        confirm = options['confirm']
        import_queue_only = options['import_queue_only']
        feature_store_only = options['feature_store_only']
        logs_only = options['logs_only']

        # Determine what to clear
        clear_import_queue = import_queue_only or (not feature_store_only and not logs_only)
        clear_feature_store = feature_store_only or (not import_queue_only and not logs_only)
        clear_logs = logs_only or (not import_queue_only and not feature_store_only)

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN MODE - No changes will be made'))

        # Get counts
        import_queue_count = ImportQueue.objects.count() if clear_import_queue else 0
        feature_store_count = FeatureStore.objects.count() if clear_feature_store else 0
        logs_count = DatabaseLogging.objects.count() if clear_logs else 0

        total_count = import_queue_count + feature_store_count + logs_count

        if total_count == 0:
            self.stdout.write(self.style.SUCCESS('All specified data stores are already empty'))
            return

        # Show what will be cleared
        self.stdout.write(self.style.WARNING('=' * 60))
        self.stdout.write(self.style.WARNING('DANGER: This will permanently delete data!'))
        self.stdout.write(self.style.WARNING('=' * 60))
        
        if clear_import_queue:
            self.stdout.write(f'Import Queue: {import_queue_count} items')
        if clear_feature_store:
            self.stdout.write(f'Feature Store: {feature_store_count} items')
        if clear_logs:
            self.stdout.write(f'Database Logs: {logs_count} items')
        
        self.stdout.write(f'Total items to delete: {total_count}')

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN: No actual deletion performed'))
            return

        # Confirmation prompt
        if not confirm:
            self.stdout.write('')
            response = input('Are you sure you want to delete all this data? Type "yes" to confirm: ')
            if response.lower() != 'yes':
                self.stdout.write(self.style.SUCCESS('Operation cancelled'))
                return

        # Perform the deletion
        with transaction.atomic():
            deleted_counts = {}
            
            if clear_import_queue and import_queue_count > 0:
                deleted_count, _ = ImportQueue.objects.all().delete()
                deleted_counts['import_queue'] = deleted_count
                self.stdout.write(
                    self.style.SUCCESS(f'Deleted {deleted_count} items from import queue')
                )

            if clear_feature_store and feature_store_count > 0:
                deleted_count, _ = FeatureStore.objects.all().delete()
                deleted_counts['feature_store'] = deleted_count
                self.stdout.write(
                    self.style.SUCCESS(f'Deleted {deleted_count} items from feature store')
                )

            if clear_logs and logs_count > 0:
                deleted_count, _ = DatabaseLogging.objects.all().delete()
                deleted_counts['logs'] = deleted_count
                self.stdout.write(
                    self.style.SUCCESS(f'Deleted {deleted_count} items from database logs')
                )

        # Summary
        total_deleted = sum(deleted_counts.values())
        self.stdout.write('')
        self.stdout.write(self.style.SUCCESS('=' * 60))
        self.stdout.write(self.style.SUCCESS(f'Successfully deleted {total_deleted} total items'))
        self.stdout.write(self.style.SUCCESS('=' * 60))
