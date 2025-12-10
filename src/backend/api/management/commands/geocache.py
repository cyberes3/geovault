"""
Management command for managing the reverse geocoding cache.

Usage:
    python manage.py geocache list          - List all cached queries
    python manage.py geocache stats         - Show cache statistics
    python manage.py geocache clear         - Clear all geocoding cache
    python manage.py geocache clear <key>   - Clear specific cache key
"""

from django.core.management.base import BaseCommand
from django.core.cache import caches
from django.conf import settings


class Command(BaseCommand):
    help = 'Manage the reverse geocoding cache'

    def add_arguments(self, parser):
        parser.add_argument(
            'action',
            type=str,
            choices=['list', 'stats', 'clear'],
            help='Action to perform: list, stats, or clear'
        )
        parser.add_argument(
            'key',
            type=str,
            nargs='?',
            help='Specific cache key to clear (only for clear action)'
        )
        parser.add_argument(
            '--limit',
            type=int,
            default=100,
            help='Maximum number of keys to list (default: 100)'
        )

    def handle(self, *args, **options):
        action = options['action']
        key = options.get('key')
        limit = options['limit']

        try:
            cache = caches['geocoding']
        except Exception as e:
            self.stdout.write(self.style.ERROR(f'Failed to get geocoding cache: {e}'))
            self.stdout.write(self.style.WARNING('Falling back to default cache'))
            cache = caches['default']

        # Check if this is a Redis cache (has _cache attribute with client)
        try:
            from django_redis import get_redis_connection
            redis_conn = get_redis_connection('geocoding')
            is_redis = True
        except Exception:
            is_redis = False
            self.stdout.write(self.style.WARNING('Not using Redis cache - limited functionality'))

        if action == 'list':
            self._list_keys(cache, redis_conn if is_redis else None, limit)
        elif action == 'stats':
            self._show_stats(cache, redis_conn if is_redis else None)
        elif action == 'clear':
            if key:
                self._clear_key(cache, key)
            else:
                self._clear_all(cache, redis_conn if is_redis else None)

    def _list_keys(self, cache, redis_conn, limit):
        """List all cached query keys."""
        self.stdout.write(self.style.SUCCESS(f'\nGeocoding Cache Keys (limit: {limit}):\n'))
        
        if not redis_conn:
            self.stdout.write(self.style.WARNING('Cannot list keys for non-Redis cache'))
            return

        try:
            # Get all keys with the geocode prefix
            pattern = 'geocode:*:geocode:*'
            keys = redis_conn.keys(pattern)
            
            if not keys:
                self.stdout.write(self.style.WARNING('No cached queries found'))
                return

            self.stdout.write(self.style.SUCCESS(f'Found {len(keys)} cached queries\n'))
            
            # Display up to limit keys
            for i, key in enumerate(keys[:limit]):
                # Decode bytes to string if needed
                if isinstance(key, bytes):
                    key = key.decode('utf-8')
                
                # Get TTL (time to live) for this key
                ttl = redis_conn.ttl(key)
                ttl_days = ttl / (24 * 60 * 60) if ttl > 0 else 0
                
                # Extract coordinates from key (format: geocode:<version>:geocode:<type>[:<radius>]:<lat>,<lon>)
                parts = key.split(':')
                if len(parts) >= 4:
                    # Skip prefix and version, get type and coords
                    key_type = parts[3] if len(parts) > 3 else 'unknown'
                    # Coords could be last part, or second-to-last if there's a radius
                    coords = parts[-1] if parts else 'unknown'
                    
                    if ttl > 0:
                        self.stdout.write(f'  {i+1}. [{key_type}] {coords} (expires in {ttl_days:.1f} days)')
                    else:
                        self.stdout.write(f'  {i+1}. [{key_type}] {coords} (no expiry)')
                else:
                    self.stdout.write(f'  {i+1}. {key}')
            
            if len(keys) > limit:
                self.stdout.write(self.style.WARNING(f'\n... and {len(keys) - limit} more keys (use --limit to see more)'))
                
        except Exception as e:
            self.stdout.write(self.style.ERROR(f'Error listing keys: {e}'))

    def _show_stats(self, cache, redis_conn):
        """Show cache statistics."""
        self.stdout.write(self.style.SUCCESS('\nGeocoding Cache Statistics:\n'))
        
        if not redis_conn:
            self.stdout.write(self.style.WARNING('Cannot show stats for non-Redis cache'))
            return

        try:
            # Get all keys with the geocode prefix
            pattern = 'geocode:*:geocode:*'
            keys = redis_conn.keys(pattern)
            
            total_keys = len(keys)
            
            # Count by type
            type_counts = {}
            expired_count = 0
            total_size = 0
            
            for key in keys:
                if isinstance(key, bytes):
                    key = key.decode('utf-8')
                
                # Extract type from key (format: geocode:<version>:geocode:<type>[:<radius>]:<lat>,<lon>)
                parts = key.split(':')
                if len(parts) >= 4:
                    key_type = parts[3]  # The type is the 4th part (index 3)
                    type_counts[key_type] = type_counts.get(key_type, 0) + 1
                
                # Check TTL
                ttl = redis_conn.ttl(key)
                if ttl <= 0 and ttl != -1:  # -1 means no expiry
                    expired_count += 1
                
                # Get approximate size
                try:
                    value = redis_conn.get(key)
                    if value:
                        total_size += len(value)
                except Exception:
                    pass
            
            self.stdout.write(f'Total cached queries: {total_keys}')
            self.stdout.write(f'Approximate cache size: {total_size / 1024:.2f} KB')
            
            if expired_count > 0:
                self.stdout.write(self.style.WARNING(f'Expired keys: {expired_count}'))
            
            if type_counts:
                self.stdout.write('\nBreakdown by query type:')
                for key_type, count in sorted(type_counts.items()):
                    self.stdout.write(f'  {key_type}: {count}')
            
            # Show Redis memory info
            info = redis_conn.info('memory')
            if 'used_memory_human' in info:
                self.stdout.write(f'\nRedis memory usage: {info["used_memory_human"]}')
            
        except Exception as e:
            self.stdout.write(self.style.ERROR(f'Error getting stats: {e}'))

    def _clear_key(self, cache, key):
        """Clear a specific cache key."""
        try:
            cache.delete(key)
            self.stdout.write(self.style.SUCCESS(f'Cleared cache key: {key}'))
        except Exception as e:
            self.stdout.write(self.style.ERROR(f'Error clearing key: {e}'))

    def _clear_all(self, cache, redis_conn):
        """Clear all geocoding cache."""
        confirm = input('Are you sure you want to clear ALL geocoding cache? (yes/no): ')
        
        if confirm.lower() != 'yes':
            self.stdout.write(self.style.WARNING('Canceled'))
            return

        try:
            if redis_conn:
                # For Redis, delete all keys with geocode prefix
                pattern = 'geocode:*:geocode:*'
                keys = redis_conn.keys(pattern)
                count = len(keys)
                
                if count > 0:
                    redis_conn.delete(*keys)
                    self.stdout.write(self.style.SUCCESS(f'Cleared {count} cached queries'))
                else:
                    self.stdout.write(self.style.WARNING('No cached queries to clear'))
            else:
                # For other cache backends, just clear everything
                cache.clear()
                self.stdout.write(self.style.SUCCESS('Cleared geocoding cache'))
                
        except Exception as e:
            self.stdout.write(self.style.ERROR(f'Error clearing cache: {e}'))

