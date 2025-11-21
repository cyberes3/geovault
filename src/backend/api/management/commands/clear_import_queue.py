from django.core.management.base import BaseCommand, CommandError
from django.db import transaction
from api.models import ImportQueue, FeatureStore, DatabaseLogging


class Command(BaseCommand):
    help = 'Clear the import queue table, handling foreign key constraints properly. With --force, also clears the database log table.'

    def add_arguments(self, parser):
        parser.add_argument(
            '--force',
            action='store_true',
            help='Force clear all import queue items, even those referenced by feature store, and also clear the database log table',
        )
        parser.add_argument(
            '--only-unimported',
            action='store_true',
            help='Only clear items that have not been imported to feature store',
        )
        parser.add_argument(
            '--dry-run',
            action='store_true',
            help='Show what would be deleted without actually deleting',
        )

    def handle(self, *args, **options):
        force = options['force']
        only_unimported = options['only_unimported']
        dry_run = options['dry_run']

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN MODE - No changes will be made'))

        with transaction.atomic():
            # Get all import queue items
            all_items = ImportQueue.objects.all()
            total_count = all_items.count()
            
            if total_count == 0:
                self.stdout.write(self.style.SUCCESS('Import queue is already empty'))
                # return
            else:
                self.stdout.write(f'Found {total_count} items in import queue')

            if only_unimported:
                # Only delete items that haven't been imported
                items_to_delete = all_items.filter(imported=False)
                count_to_delete = items_to_delete.count()
                self.stdout.write(f'Found {count_to_delete} unimported items to delete')
                
                if count_to_delete == 0:
                    self.stdout.write(self.style.SUCCESS('No unimported items to delete'))
                    return
            else:
                # Check for items referenced by feature store
                referenced_items = FeatureStore.objects.filter(source__isnull=False).values_list('source_id', flat=True)
                referenced_count = len(referenced_items)
                
                if referenced_count > 0 and not force:
                    self.stdout.write(
                        self.style.ERROR(
                            f'Found {referenced_count} import queue items that are referenced by feature store.\n'
                            'Use --force to delete them anyway (will set source to NULL in feature store),\n'
                            'or use --only-unimported to only delete unreferenced items.'
                        )
                    )
                    return
                
                items_to_delete = all_items
                count_to_delete = total_count

            if dry_run:
                self.stdout.write(f'Would delete {count_to_delete} items from import queue')
                if not only_unimported and referenced_count > 0:
                    self.stdout.write(f'Would set source to NULL for {referenced_count} feature store items')
                return

            # Delete the items
            deleted_count, _ = items_to_delete.delete()
            
            self.stdout.write(
                self.style.SUCCESS(
                    f'Successfully deleted {deleted_count} items from import queue'
                )
            )

            if not only_unimported and referenced_count > 0:
                # Update feature store items to set source to NULL
                updated_count = FeatureStore.objects.filter(source__isnull=False).update(source=None)
                self.stdout.write(
                    self.style.SUCCESS(
                        f'Updated {updated_count} feature store items to remove source references'
                    )
                )

            # Clear database log table if --force is used
            if force:
                log_count = DatabaseLogging.objects.count()
                if log_count > 0:
                    if dry_run:
                        self.stdout.write(f'Would delete {log_count} entries from database log table')
                    else:
                        DatabaseLogging.objects.all().delete()
                        self.stdout.write(
                            self.style.SUCCESS(
                                f'Successfully deleted {log_count} entries from database log table'
                            )
                        )
                else:
                    self.stdout.write('Database log table is already empty')
