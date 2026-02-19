import json
import traceback
from django.http import JsonResponse, HttpResponseRedirect
from django.views.decorators.http import require_http_methods
from django.core.cache import cache
from django.utils import timezone
from allauth.account.forms import ChangePasswordForm
from allauth.account.models import EmailAddress

from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401
from users.constants import (
    EMAIL_VERIFICATION_CACHE_KEY,
    EMAIL_VERIFICATION_COOLDOWN_SECONDS,
)

_logger = get_tagged_logger(__name__)


@api_or_login_required_401(allow_api_keys=False)  # Password changes should only be via session
@require_http_methods(["POST"])
def change_password_api(request):
    """API endpoint for changing user password using allauth's ChangePasswordForm."""
    try:
        form = ChangePasswordForm(user=request.user, data=json.loads(request.body))
        if form.is_valid():
            form.save()
            return JsonResponse({
                'message': 'Password changed successfully.'
            })
        else:
            # Extract form errors
            errors = {}
            for field, field_errors in form.errors.items():
                errors[field] = field_errors[0] if field_errors else 'Invalid value'
            
            # Return first error message for simplicity
            first_error = list(errors.values())[0] if errors else 'Invalid form data'
            return JsonResponse({
                'error': first_error,
                'errors': errors
            }, status=400)
    except json.JSONDecodeError:
        return JsonResponse({
            'error': 'Invalid JSON data'
        }, status=400)
    except Exception:
        _logger.error("Failed to change password:\n%s", traceback.format_exc())
        return JsonResponse({'error': 'Failed to change password'}, status=500)


@api_or_login_required_401(allow_api_keys=False)  # Email status should only be via session
@require_http_methods(["GET"])
def get_email_status_api(request):
    """API endpoint to get current user's email addresses and verification status."""
    try:
        email_addresses = EmailAddress.objects.filter(user=request.user).order_by('-primary', '-verified', 'email')
        
        emails = []
        primary_email = None
        pending_emails = []
        
        for email_addr in email_addresses:
            email_data = {
                'email': email_addr.email,
                'verified': email_addr.verified,
                'primary': email_addr.primary
            }
            emails.append(email_data)
            
            if email_addr.primary:
                primary_email = email_addr.email
            
            if not email_addr.verified:
                pending_emails.append(email_addr.email)
        
        # Check cooldown status for primary unverified email
        cooldown_remaining = None
        on_cooldown = False
        if primary_email:
            # Check if primary email is unverified
            primary_email_data = next((e for e in emails if e['email'] == primary_email), None)
            if primary_email_data and not primary_email_data['verified']:
                cache_key = EMAIL_VERIFICATION_CACHE_KEY.format(
                    user_id=request.user.id,
                    email=primary_email
                )
                last_sent_time = cache.get(cache_key)
                if last_sent_time:
                    elapsed = (timezone.now() - last_sent_time).total_seconds()
                    remaining = EMAIL_VERIFICATION_COOLDOWN_SECONDS - elapsed
                    if remaining > 0:
                        cooldown_remaining = int(remaining)
                        on_cooldown = True
        
        return JsonResponse({
            'emails': emails,
            'primary_email': primary_email or (emails[0]['email'] if emails else None),
            'pending_verification': pending_emails,
            'has_unverified': len(pending_emails) > 0,
            'resend_cooldown_remaining': cooldown_remaining,
            'resend_on_cooldown': on_cooldown
        })
    except Exception:
        _logger.error("Failed to get email status:\n%s", traceback.format_exc())
        return JsonResponse({'error': 'Failed to get email status'}, status=500)


@api_or_login_required_401(allow_api_keys=False)  # Email verification should only be via session
@require_http_methods(["POST"])
def resend_verification_api(request):
    """API endpoint to resend verification email for an email address with 1-minute cooldown."""
    try:
        data = json.loads(request.body)
        email = data.get('email', '').strip()
        
        if not email:
            return JsonResponse({
                'error': 'Email address is required'
            }, status=400)
        
        # Find the email address for this user
        try:
            email_address = EmailAddress.objects.get(user=request.user, email=email)
        except EmailAddress.DoesNotExist:
            return JsonResponse({
                'error': 'Email address not found'
            }, status=404)
        
        if email_address.verified:
            return JsonResponse({
                'error': 'Email address is already verified'
            }, status=400)
        
        # Check cooldown: 1 minute between resends
        cache_key = EMAIL_VERIFICATION_CACHE_KEY.format(
            user_id=request.user.id,
            email=email
        )
        last_sent_time = cache.get(cache_key)
        
        if last_sent_time:
            # Calculate remaining cooldown time
            elapsed = (timezone.now() - last_sent_time).total_seconds()
            remaining = EMAIL_VERIFICATION_COOLDOWN_SECONDS - elapsed
            
            if remaining > 0:
                return JsonResponse({
                    'error': 'Please wait before requesting another verification email',
                    'cooldown_remaining': int(remaining),
                    'on_cooldown': True
                }, status=429)  # 429 Too Many Requests
        
        # Resend verification email using EmailAddress.send_confirmation method
        email_address.send_confirmation(request)
        
        # Store the current time in cache for 1 minute
        cache.set(cache_key, timezone.now(), timeout=EMAIL_VERIFICATION_COOLDOWN_SECONDS)
        
        return JsonResponse({
            'message': f'Verification email sent to {email}. Please check your inbox.',
            'cooldown_remaining': EMAIL_VERIFICATION_COOLDOWN_SECONDS,
            'on_cooldown': False
        })
    except json.JSONDecodeError:
        return JsonResponse({
            'error': 'Invalid JSON data'
        }, status=400)
    except Exception:
        _logger.error("Failed to resend verification email:\n%s", traceback.format_exc())
        return JsonResponse({'error': 'Failed to resend verification email'}, status=500)


def block_account_email_view(request):
    """Redirect /accounts/email/ to the frontend settings page."""
    return HttpResponseRedirect('/#/settings?tab=account')

