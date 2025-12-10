import traceback

from allauth.account.models import EmailAddress
from django.contrib.auth import get_user_model
from django.db import connection
from django.db.models import Count, Q
from django.http import JsonResponse

from api.models import FeatureStore, ImportQueue, TagShare, CollectionShare
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401
from users.models import UserProfile

User = get_user_model()


@api_or_login_required_401(allow_api_keys=False)  # Admin routes should only be accessible via session
def list_all_users(request):
    """
    List all users with their statistics (admin only).
    Returns email, last activity, creation date, feature count, share count, and storage usage.
    """
    # Check if user is superuser
    if not request.user.is_superuser:
        return JsonResponse({'error': 'Forbidden'}, status=403)

    try:
        # Get all users
        users = User.objects.all().order_by('-date_joined')

        # Get table names for storage calculation
        feature_store_table = FeatureStore._meta.db_table
        import_queue_table = ImportQueue._meta.db_table

        # Pre-fetch email addresses for all users
        user_ids = [user.id for user in users]
        email_addresses = EmailAddress.objects.filter(
            user_id__in=user_ids
        ).select_related('user')

        # Create email lookup dict (primary email preferred, fallback to first)
        email_map = {}
        for email_addr in email_addresses:
            user_id = email_addr.user_id
            if user_id not in email_map:
                email_map[user_id] = email_addr.email
            elif email_addr.primary:
                email_map[user_id] = email_addr.email

        # Get feature counts for all users using aggregation
        feature_counts = dict(
            FeatureStore.objects.filter(user_id__in=user_ids)
            .values('user_id')
            .annotate(count=Count('id'))
            .values_list('user_id', 'count')
        )

        # Get share counts (TagShare + CollectionShare) for all users
        tag_share_counts = dict(
            TagShare.objects.filter(user_id__in=user_ids)
            .values('user_id')
            .annotate(count=Count('id'))
            .values_list('user_id', 'count')
        )

        collection_share_counts = dict(
            CollectionShare.objects.filter(user_id__in=user_ids)
            .values('user_id')
            .annotate(count=Count('id'))
            .values_list('user_id', 'count')
        )

        # Pre-fetch user profiles for last_activity
        user_profiles = {
            profile.user_id: profile
            for profile in UserProfile.objects.filter(user_id__in=user_ids).select_related('user')
        }

        # Calculate storage usage for all users in batch
        # Use raw SQL for efficient calculation
        storage_map = {}
        if user_ids:  # Only query if there are users
            with connection.cursor() as cursor:
                # Get FeatureStore storage for all users
                cursor.execute(f"""
                    SELECT user_id, COALESCE(SUM(octet_length(geojson::text)), 0) as size
                    FROM {feature_store_table}
                    WHERE user_id = ANY(%s)
                    GROUP BY user_id
                """, [user_ids])
                for row in cursor.fetchall():
                    user_id, size = row
                    storage_map[user_id] = size or 0

                # Add ImportQueue storage for all users
                cursor.execute(f"""
                    SELECT user_id, COALESCE(SUM(octet_length(raw_file)), 0) as size
                    FROM {import_queue_table}
                    WHERE user_id = ANY(%s)
                    GROUP BY user_id
                """, [user_ids])
                for row in cursor.fetchall():
                    user_id, size = row
                    storage_map[user_id] = storage_map.get(user_id, 0) + (size or 0)

        # Build response data
        users_data = []
        for user in users:
            # Calculate total share count
            tag_shares = tag_share_counts.get(user.id, 0)
            collection_shares = collection_share_counts.get(user.id, 0)
            total_shares = tag_shares + collection_shares

            # Get last activity from profile, fallback to last_login if profile doesn't exist
            profile = user_profiles.get(user.id)
            if profile and profile.last_activity:
                last_activity = profile.last_activity.isoformat()
            elif user.last_login:
                # Fallback to last_login for users without activity tracking yet
                last_activity = user.last_login.isoformat()
            else:
                last_activity = None

            users_data.append({
                'id': user.id,
                'email': email_map.get(user.id, None),
                'last_activity': last_activity,
                'date_joined': user.date_joined.isoformat() if user.date_joined else None,
                'feature_count': feature_counts.get(user.id, 0),
                'share_count': total_shares,
                'storage_bytes': storage_map.get(user.id, 0)
            })

        return JsonResponse({
            'users': users_data
        })

    except Exception as e:
        logger = get_tagged_logger('access')
        logger.error(f"Error listing users for admin:\n{traceback.format_exc()}")
        return JsonResponse({
            'error': 'Failed to list users',
            'message': str(e)
        }, status=500)

