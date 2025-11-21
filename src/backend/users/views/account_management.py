from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from django.core.cache import cache
from django.utils import timezone
from allauth.account.forms import ChangePasswordForm, AddEmailForm
from allauth.account.models import EmailAddress
from geo_lib.website.auth import login_required_401
import json


@login_required_401
@require_http_methods(["POST"])
def change_password_api(request):
    """API endpoint for changing user password using allauth's ChangePasswordForm."""
    try:
        form = ChangePasswordForm(user=request.user, data=json.loads(request.body))
        if form.is_valid():
            form.save()
            return JsonResponse({
                'success': True,
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
                'success': False,
                'error': first_error,
                'errors': errors
            }, status=400)
    except json.JSONDecodeError:
        return JsonResponse({
            'success': False,
            'error': 'Invalid JSON data'
        }, status=400)
    except Exception as e:
        return JsonResponse({
            'success': False,
            'error': f'An error occurred: {str(e)}'
        }, status=500)


@login_required_401
@require_http_methods(["POST"])
def email_management_api(request):
    """API endpoint for changing email address. Replaces existing email with new one."""
    try:
        data = json.loads(request.body)
        new_email = data.get('email', '').strip()
        
        if not new_email:
            return JsonResponse({
                'success': False,
                'error': 'Email address is required'
            }, status=400)
        
        # Check if the new email is the same as current primary email
        existing_primary = EmailAddress.objects.filter(user=request.user, primary=True).first()
        if existing_primary and existing_primary.email.lower() == new_email.lower():
            return JsonResponse({
                'success': False,
                'error': 'This is already your current email address'
            }, status=400)
        
        # Validate the email using AddEmailForm
        form = AddEmailForm(user=request.user, data={'email': new_email})
        
        if form.is_valid():
            # Delete all existing email addresses for this user
            EmailAddress.objects.filter(user=request.user).delete()
            
            # Add the new email address as primary
            email_address = form.save(request)
            email_address.primary = True
            email_address.save()
            
            # Update user's email field if it exists
            if hasattr(request.user, 'email'):
                request.user.email = new_email
                request.user.save()
            
            # Allauth automatically sends verification email when ACCOUNT_EMAIL_VERIFICATION is mandatory
            return JsonResponse({
                'success': True,
                'message': 'Email address changed. Please check your email to verify it.',
                'email': email_address.email,
                'verified': email_address.verified,
                'pending_verification': not email_address.verified
            })
        else:
            # Extract form errors
            errors = {}
            for field, field_errors in form.errors.items():
                errors[field] = field_errors[0] if field_errors else 'Invalid value'
            
            first_error = list(errors.values())[0] if errors else 'Invalid form data'
            return JsonResponse({
                'success': False,
                'error': first_error,
                'errors': errors
            }, status=400)
    except json.JSONDecodeError:
        return JsonResponse({
            'success': False,
            'error': 'Invalid JSON data'
        }, status=400)
    except Exception as e:
        return JsonResponse({
            'success': False,
            'error': f'An error occurred: {str(e)}'
        }, status=500)


@login_required_401
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
                cache_key = f'email_verification_resend_{request.user.id}_{primary_email}'
                last_sent_time = cache.get(cache_key)
                if last_sent_time:
                    cooldown_seconds = 60
                    elapsed = (timezone.now() - last_sent_time).total_seconds()
                    remaining = cooldown_seconds - elapsed
                    if remaining > 0:
                        cooldown_remaining = int(remaining)
                        on_cooldown = True
        
        return JsonResponse({
            'success': True,
            'emails': emails,
            'primary_email': primary_email or (emails[0]['email'] if emails else None),
            'pending_verification': pending_emails,
            'has_unverified': len(pending_emails) > 0,
            'resend_cooldown_remaining': cooldown_remaining,
            'resend_on_cooldown': on_cooldown
        })
    except Exception as e:
        return JsonResponse({
            'success': False,
            'error': f'An error occurred: {str(e)}'
        }, status=500)


@login_required_401
@require_http_methods(["POST"])
def resend_verification_api(request):
    """API endpoint to resend verification email for an email address with 1-minute cooldown."""
    try:
        data = json.loads(request.body)
        email = data.get('email', '').strip()
        
        if not email:
            return JsonResponse({
                'success': False,
                'error': 'Email address is required'
            }, status=400)
        
        # Find the email address for this user
        try:
            email_address = EmailAddress.objects.get(user=request.user, email=email)
        except EmailAddress.DoesNotExist:
            return JsonResponse({
                'success': False,
                'error': 'Email address not found'
            }, status=404)
        
        if email_address.verified:
            return JsonResponse({
                'success': False,
                'error': 'Email address is already verified'
            }, status=400)
        
        # Check cooldown: 1 minute between resends
        cache_key = f'email_verification_resend_{request.user.id}_{email}'
        last_sent_time = cache.get(cache_key)
        
        if last_sent_time:
            # Calculate remaining cooldown time
            cooldown_seconds = 60  # 1 minute
            elapsed = (timezone.now() - last_sent_time).total_seconds()
            remaining = cooldown_seconds - elapsed
            
            if remaining > 0:
                return JsonResponse({
                    'success': False,
                    'error': 'Please wait before requesting another verification email',
                    'cooldown_remaining': int(remaining),
                    'on_cooldown': True
                }, status=429)  # 429 Too Many Requests
        
        # Resend verification email using EmailAddress.send_confirmation method
        email_address.send_confirmation(request)
        
        # Store the current time in cache for 1 minute
        cache.set(cache_key, timezone.now(), timeout=60)
        
        return JsonResponse({
            'success': True,
            'message': f'Verification email sent to {email}. Please check your inbox.',
            'cooldown_remaining': 60,
            'on_cooldown': False
        })
    except json.JSONDecodeError:
        return JsonResponse({
            'success': False,
            'error': 'Invalid JSON data'
        }, status=400)
    except Exception as e:
        return JsonResponse({
            'success': False,
            'error': f'An error occurred: {str(e)}'
        }, status=500)

