"""
Constants for the users app, including cache key formats.
"""

# Cache key format for email verification cooldown
# This key is used by both:
# 1. The NoUsernameAccountAdapter.send_confirmation_mail() method
# 2. The resend_verification_api() view
# Using a shared key ensures both systems coordinate to prevent duplicate emails
EMAIL_VERIFICATION_CACHE_KEY = 'email_verification_resend_{user_id}_{email}'

# Cooldown duration in seconds
EMAIL_VERIFICATION_COOLDOWN_SECONDS = 60  # 1 minute


