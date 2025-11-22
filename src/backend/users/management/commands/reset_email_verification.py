"""
Django management command to reset email verification status for testing.
"""
from django.core.management.base import BaseCommand
from django.contrib.auth import get_user_model
from allauth.account.models import EmailAddress

User = get_user_model()


class Command(BaseCommand):
    help = 'Reset email verification status for a user (for testing)'

    def add_arguments(self, parser):
        parser.add_argument(
            'email',
            type=str,
            help='Email address of the user to reset verification for'
        )
        parser.add_argument(
            '--verify',
            action='store_true',
            help='Set email as verified instead of unverified',
        )

    def handle(self, *args, **options):
        """Reset email verification status."""
        email = options['email']
        verify = options.get('verify', False)
        
        try:
            # Find user by email
            user = User.objects.get(email=email)
            
            # Get or create EmailAddress
            email_address, created = EmailAddress.objects.get_or_create(
                user=user,
                email=email,
                defaults={'primary': True, 'verified': verify}
            )
            
            if not created:
                email_address.verified = verify
                email_address.save()
            
            status = 'verified' if verify else 'unverified'
            self.stdout.write(
                self.style.SUCCESS(
                    f'Successfully set email {email} to {status} for user {email}'
                )
            )
            
        except User.DoesNotExist:
            self.stdout.write(
                self.style.ERROR(f'User with email {email} not found')
            )
        except Exception as e:
            self.stdout.write(
                self.style.ERROR(f'Error: {str(e)}')
            )

