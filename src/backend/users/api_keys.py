import hashlib
import secrets
from django.utils import timezone
from .models import ApiKey, User


def generate_api_key() -> str:
    """
    Generate a new 64-character API key starting with 'gv_'.
    
    Returns:
        A 64-character string starting with 'gv_' followed by random characters.
    """
    # 'gv_' is 3 characters, so we need 61 more to reach 64 total
    random_part = secrets.token_urlsafe(45)  # Generates ~60 chars, we'll trim to exactly 61
    # Ensure exactly 64 characters total: 'gv_' + 61 random chars
    random_part = random_part[:61]
    return f"gv_{random_part}"


def hash_api_key(raw_key: str) -> str:
    """
    Hash an API key using SHA-256.
    
    Args:
        raw_key: The raw API key string
        
    Returns:
        The hexadecimal digest of the SHA-256 hash
    """
    return hashlib.sha256(raw_key.encode('utf-8')).hexdigest()


def create_user_api_key(user: User, name: str) -> tuple[ApiKey, str]:
    """
    Create a new API key for a user.
    
    Args:
        user: The user to create the key for
        name: User-provided name/label for the key
        
    Returns:
        A tuple of (ApiKey instance, raw_key_string)
        The raw_key_string should be shown to the user only once.
    """
    # Generate the key
    raw_key = generate_api_key()
    
    # Extract prefix (first 8 characters)
    key_prefix = raw_key[:8]
    
    # Hash the full key
    key_hash = hash_api_key(raw_key)
    
    # Create the database record
    api_key = ApiKey.objects.create(
        user=user,
        name=name,
        key_prefix=key_prefix,
        key_hash=key_hash,
        is_active=True
    )
    
    return api_key, raw_key


def validate_api_key(raw_key: str) -> tuple[User, ApiKey] | None:
    """
    Validate an API key and return the associated user and key object.
    
    Uses constant-time comparison to prevent timing attacks.
    Updates last_used_at on successful validation.
    
    Args:
        raw_key: The raw API key string to validate
        
    Returns:
        A tuple of (User, ApiKey) if valid, None otherwise
    """
    if not raw_key or len(raw_key) < 8:
        return None
    
    # Extract prefix for lookup
    key_prefix = raw_key[:8]
    
    # Look up all keys with this prefix (should be very few due to uniqueness constraint)
    matching_keys = ApiKey.objects.filter(
        key_prefix=key_prefix,
        is_active=True
    ).select_related('user')
    
    # Hash the provided key
    provided_hash = hash_api_key(raw_key)
    
    # Check each matching key using constant-time comparison
    for api_key in matching_keys:
        # Use constant-time comparison to prevent timing attacks
        if secrets.compare_digest(api_key.key_hash, provided_hash):
            # Update last_used_at
            api_key.last_used_at = timezone.now()
            api_key.save(update_fields=['last_used_at'])
            return api_key.user, api_key
    
    return None


