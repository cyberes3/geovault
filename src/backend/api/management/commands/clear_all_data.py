from django.core.management.base import BaseCommand, CommandError
from django.db import transaction
from django.contrib.auth import get_user_model
from api.models import ImportQueue, FeatureStore, DatabaseLogging, TagShare, CollectionShare, Collection


class Command(BaseCommand):
    help = 'Clear all data: import queue, feature store, database logs, tag shares, collection shares, and collections. Use with caution!'

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
            '--user',
            type=str,
            help='Email address of the user whose data should be cleared. If not provided, clears data for all users.',
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
        parser.add_argument(
            '--tag-shares-only',
            action='store_true',
            help='Only clear tag shares',
        )
        parser.add_argument(
            '--collection-shares-only',
            action='store_true',
            help='Only clear collection shares',
        )
        parser.add_argument(
            '--collections-only',
            action='store_true',
            help='Only clear collections',
        )

    def handle(self, *args, **options):
        dry_run = options['dry_run']
        confirm = options['confirm']
        user_email = options.get('user')
        import_queue_only = options['import_queue_only']
        feature_store_only = options['feature_store_only']
        logs_only = options['logs_only']
        tag_shares_only = options['tag_shares_only']
        collection_shares_only = options['collection_shares_only']
        collections_only = options['collections_only']

        # Get user object if email is provided
        user_obj = None
        if user_email:
            User = get_user_model()
            try:
                user_obj = User.objects.get(email=user_email)
                self.stdout.write(self.style.WARNING(f'Filtering data for user: {user_email}'))
            except User.DoesNotExist:
                raise CommandError(f'User with email "{user_email}" does not exist')
            except User.MultipleObjectsReturned:
                raise CommandError(f'Multiple users found with email "{user_email}"')

        # Determine what to clear
        # If any "only" flag is set, only clear that specific table
        # Otherwise, clear all tables
        any_only_flag = (import_queue_only or feature_store_only or logs_only or 
                        tag_shares_only or collection_shares_only or collections_only)
        
        clear_import_queue = import_queue_only or (not any_only_flag)
        clear_feature_store = feature_store_only or (not any_only_flag)
        clear_logs = logs_only or (not any_only_flag)
        clear_tag_shares = tag_shares_only or (not any_only_flag)
        clear_collection_shares = collection_shares_only or (not any_only_flag)
        clear_collections = collections_only or (not any_only_flag)

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN MODE - No changes will be made'))

        # Get counts (filter by user if specified)
        import_queue_qs = ImportQueue.objects.filter(user=user_obj) if user_obj else ImportQueue.objects.all()
        feature_store_qs = FeatureStore.objects.filter(user=user_obj) if user_obj else FeatureStore.objects.all()
        logs_qs = DatabaseLogging.objects.filter(user=user_obj) if user_obj else DatabaseLogging.objects.all()
        tag_shares_qs = TagShare.objects.filter(user=user_obj) if user_obj else TagShare.objects.all()
        collection_shares_qs = CollectionShare.objects.filter(user=user_obj) if user_obj else CollectionShare.objects.all()
        collections_qs = Collection.objects.filter(user=user_obj) if user_obj else Collection.objects.all()

        import_queue_count = import_queue_qs.count() if clear_import_queue else 0
        feature_store_count = feature_store_qs.count() if clear_feature_store else 0
        logs_count = logs_qs.count() if clear_logs else 0
        tag_shares_count = tag_shares_qs.count() if clear_tag_shares else 0
        collection_shares_count = collection_shares_qs.count() if clear_collection_shares else 0
        collections_count = collections_qs.count() if clear_collections else 0

        total_count = (import_queue_count + feature_store_count + logs_count + 
                      tag_shares_count + collection_shares_count + collections_count)

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
        if clear_tag_shares:
            self.stdout.write(f'Tag Shares: {tag_shares_count} items')
        if clear_collection_shares:
            self.stdout.write(f'Collection Shares: {collection_shares_count} items')
        if clear_collections:
            self.stdout.write(f'Collections: {collections_count} items')
        
        self.stdout.write(f'Total items to delete: {total_count}')

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN: No actual deletion performed'))
            return

        # Confirmation prompt
        if not confirm:
            self.stdout.write('')
            response = input('Are you sure you want to delete all this data? Type "yes" to confirm: ')
            if response.lower() != 'yes':
                self.stdout.write(self.style.SUCCESS('Operation canceled'))
                return

        # Perform the deletion
        # Note: CollectionShare must be deleted before Collection due to FK constraint
        # Logs are deleted first since they're just metadata about the other data
        with transaction.atomic():
            deleted_counts = {}
            
            if clear_logs and logs_count > 0:
                deleted_count, _ = logs_qs.delete()
                deleted_counts['logs'] = deleted_count
                self.stdout.write(
                    self.style.SUCCESS(f'Deleted {deleted_count} items from database logs')
                )

            if clear_collection_shares and collection_shares_count > 0:
                deleted_count, _ = collection_shares_qs.delete()
                deleted_counts['collection_shares'] = deleted_count
                self.stdout.write(
                    self.style.SUCCESS(f'Deleted {deleted_count} items from collection shares')
                )

            if clear_collections and collections_count > 0:
                deleted_count, _ = collections_qs.delete()
                deleted_counts['collections'] = deleted_count
                self.stdout.write(
                    self.style.SUCCESS(f'Deleted {deleted_count} items from collections')
                )

            if clear_tag_shares and tag_shares_count > 0:
                deleted_count, _ = tag_shares_qs.delete()
                deleted_counts['tag_shares'] = deleted_count
                self.stdout.write(
                    self.style.SUCCESS(f'Deleted {deleted_count} items from tag shares')
                )

            if clear_import_queue and import_queue_count > 0:
                deleted_count, _ = import_queue_qs.delete()
                deleted_counts['import_queue'] = deleted_count
                self.stdout.write(
                    self.style.SUCCESS(f'Deleted {deleted_count} items from import queue')
                )

            if clear_feature_store and feature_store_count > 0:
                deleted_count, _ = feature_store_qs.delete()
                deleted_counts['feature_store'] = deleted_count
                self.stdout.write(
                    self.style.SUCCESS(f'Deleted {deleted_count} items from feature store')
                )

        # Summary
        total_deleted = sum(deleted_counts.values())
        self.stdout.write('')
        self.stdout.write(self.style.SUCCESS('=' * 60))
        self.stdout.write(self.style.SUCCESS(f'Successfully deleted {total_deleted} total items'))
        self.stdout.write(self.style.SUCCESS('=' * 60))
