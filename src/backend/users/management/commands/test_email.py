"""
Django management command to test email configuration.
"""
from django.core.management.base import BaseCommand
from django.core.mail import send_mail
from django.conf import settings
import logging

logger = logging.getLogger(__name__)


class Command(BaseCommand):
    help = 'Test email configuration by sending a test email'

    def add_arguments(self, parser):
        parser.add_argument(
            'email',
            type=str,
            help='Email address to send test email to'
        )

    def handle(self, *args, **options):
        """Send a test email."""
        test_email_address = options['email']
        
        # Display email configuration
        self.stdout.write('\nEmail Configuration:')
        self.stdout.write(f"  Backend: {settings.EMAIL_BACKEND}")
        self.stdout.write(f"  Host: {settings.EMAIL_HOST}")
        self.stdout.write(f"  Port: {settings.EMAIL_PORT}")
        self.stdout.write(f"  Use TLS: {settings.EMAIL_USE_TLS}")
        self.stdout.write(f"  Use SSL: {settings.EMAIL_USE_SSL}")
        self.stdout.write(f"  Username: {settings.EMAIL_HOST_USER}")
        self.stdout.write(f"  Password Set: {'Yes' if settings.EMAIL_HOST_PASSWORD else 'No'}")
        self.stdout.write(f"  From Email: {settings.DEFAULT_FROM_EMAIL}")
        self.stdout.write('')
        
        try:
            self.stdout.write(f'Sending test email to {test_email_address}...')
            send_mail(
                subject='Test Email from GeoVault',
                message='This is a test email from GeoVault. If you receive this, email is working correctly!',
                from_email=settings.DEFAULT_FROM_EMAIL,
                recipient_list=[test_email_address],
                fail_silently=False,
            )
            logger.info(f"Email sent to {test_email_address} - test")
            self.stdout.write(
                self.style.SUCCESS(f'✓ Test email sent successfully to {test_email_address}!')
            )
            self.stdout.write('Check your inbox for the test email.')
        except Exception as e:
            logger.error(f"Failed to send test email to {test_email_address}: {type(e).__name__}: {str(e)}", exc_info=True)
            self.stdout.write(
                self.style.ERROR(f'✗ Failed to send test email: {type(e).__name__}: {str(e)}')
            )
            raise



