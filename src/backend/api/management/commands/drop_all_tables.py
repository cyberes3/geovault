from django.core.management.base import BaseCommand, CommandError
from django.db import connection


class Command(BaseCommand):
    help = 'Drop all tables in the database. This is a DESTRUCTIVE operation that will delete all tables, including Django system tables. Use with extreme caution!'

    def add_arguments(self, parser):
        parser.add_argument(
            '--dry-run',
            action='store_true',
            help='Show what would be dropped without actually dropping',
        )
        parser.add_argument(
            '--confirm',
            action='store_true',
            help='Skip confirmation prompt (useful for scripts)',
        )

    def handle(self, *args, **options):
        dry_run = options['dry_run']
        confirm = options['confirm']

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN MODE - No changes will be made'))

        # Get all tables in the public schema
        try:
            with connection.cursor() as cursor:
                cursor.execute("""
                    SELECT table_name 
                    FROM information_schema.tables 
                    WHERE table_schema = 'public' 
                    AND table_type = 'BASE TABLE'
                    ORDER BY table_name
                """)
                tables = [row[0] for row in cursor.fetchall()]
        except Exception as e:
            raise CommandError(f'Failed to query database tables: {e}')

        if not tables:
            self.stdout.write(self.style.SUCCESS('No tables found in the database'))
            return

        # Show what will be dropped
        self.stdout.write(self.style.WARNING('=' * 60))
        self.stdout.write(self.style.WARNING('DANGER: This will permanently drop ALL tables!'))
        self.stdout.write(self.style.WARNING('=' * 60))
        self.stdout.write('')
        self.stdout.write(f'Found {len(tables)} table(s) to drop:')
        for table in tables:
            self.stdout.write(f'  - {table}')
        self.stdout.write('')

        if dry_run:
            self.stdout.write(self.style.WARNING('DRY RUN: No tables were actually dropped'))
            return

        # Confirmation prompt
        if not confirm:
            self.stdout.write('')
            response = input('Are you sure you want to drop ALL tables? Type "yes" to confirm: ')
            if response.lower() != 'yes':
                self.stdout.write(self.style.SUCCESS('Operation cancelled'))
                return

        # Drop all tables
        try:
            with connection.cursor() as cursor:
                # Disable foreign key checks temporarily by using CASCADE
                # Drop each table with CASCADE to handle foreign key constraints
                dropped_count = 0
                for table in tables:
                    try:
                        cursor.execute(f'DROP TABLE IF EXISTS "{table}" CASCADE')
                        dropped_count += 1
                        self.stdout.write(
                            self.style.SUCCESS(f'Dropped table: {table}')
                        )
                    except Exception as e:
                        self.stdout.write(
                            self.style.ERROR(f'Failed to drop table {table}: {e}')
                        )
                        # Continue with other tables even if one fails
        except Exception as e:
            raise CommandError(f'Failed to drop tables: {e}')

        # Summary
        self.stdout.write('')
        self.stdout.write(self.style.SUCCESS('=' * 60))
        self.stdout.write(self.style.SUCCESS(f'Successfully dropped {dropped_count} out of {len(tables)} table(s)'))
        self.stdout.write(self.style.SUCCESS('=' * 60))

