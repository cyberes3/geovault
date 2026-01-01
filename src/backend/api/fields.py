"""
Custom Django model fields with encryption support.
"""
import base64
import hashlib
from django.conf import settings
from django.db import models
from cryptography.fernet import Fernet, InvalidToken
from geo_lib.logging.console import get_tagged_logger

_logger = get_tagged_logger('EncryptedField')

# Module-level Fernet instance (initialized once, reused for all field instances)
_fernet_instance = None


def _get_fernet_instance():
    """
    Get or create the module-level Fernet instance.
    Derives encryption key from Django's SECRET_KEY setting.
    
    Returns:
        Fernet instance for encryption/decryption
    """
    global _fernet_instance
    
    if _fernet_instance is None:
        # Derive 32-byte key from SECRET_KEY using SHA-256
        secret_key = settings.SECRET_KEY
        key_bytes = hashlib.sha256(secret_key.encode()).digest()
        
        # Convert to URL-safe base64 format required by Fernet
        fernet_key = base64.urlsafe_b64encode(key_bytes)
        
        # Create Fernet instance
        _fernet_instance = Fernet(fernet_key)
        _logger.debug("Initialized Fernet instance from SECRET_KEY")
    
    return _fernet_instance


class EncryptedTextField(models.TextField):
    """
    A TextField that automatically encrypts data before saving to the database
    and decrypts it when retrieving.
    
    Uses Fernet symmetric encryption with a key derived from Django's SECRET_KEY.
    
    Example:
        class MyModel(models.Model):
            secret = EncryptedTextField(help_text="Encrypted secret data")
    """
    
    def __init__(self, *args, **kwargs):
        """Initialize the encrypted field."""
        super().__init__(*args, **kwargs)
        self._fernet = _get_fernet_instance()
    
    def get_prep_value(self, value):
        """
        Encrypt the value before saving to the database.
        
        Args:
            value: Plaintext string to encrypt (or None)
            
        Returns:
            Encrypted string (base64-encoded) or None
        """
        if value is None:
            return None
        
        if not isinstance(value, str):
            # Convert to string if needed
            value = str(value)
        
        try:
            # Encrypt the value
            encrypted_bytes = self._fernet.encrypt(value.encode('utf-8'))
            # Return as string (Fernet tokens are already URL-safe base64)
            return encrypted_bytes.decode('utf-8')
        except Exception as e:
            _logger.error(f"Error encrypting field value: {e}", exc_info=True)
            raise
    
    def from_db_value(self, value, expression, connection):
        """
        Decrypt the value when loading from the database.
        
        Args:
            value: Encrypted string from database (or None)
            expression: The expression that produced this value
            connection: The database connection
            
        Returns:
            Decrypted plaintext string or None
        """
        if value is None:
            return None
        
        if not isinstance(value, str):
            # If it's not a string, return as-is (shouldn't happen)
            return value
        
        try:
            # Decrypt the value
            decrypted_bytes = self._fernet.decrypt(value.encode('utf-8'))
            return decrypted_bytes.decode('utf-8')
        except InvalidToken as e:
            # This could happen if:
            # - Data was encrypted with a different key
            # - Data is corrupted
            # - Data is plaintext (from before encryption was added)
            _logger.warning(
                f"Failed to decrypt field value (InvalidToken). "
                f"This may be plaintext data from before encryption was added, "
                f"or data encrypted with a different key: {e}"
            )
            # Return the value as-is (might be plaintext from migration)
            return value
        except Exception as e:
            _logger.error(f"Error decrypting field value: {e}", exc_info=True)
            # Return the value as-is rather than crashing
            return value
    
    def to_python(self, value):
        """
        Convert the value to Python string, decrypting if needed.
        
        This is called when the value is accessed in Python code.
        
        Args:
            value: Value from database or Python code
            
        Returns:
            Decrypted plaintext string or None
        """
        if value is None:
            return None
        
        if isinstance(value, str):
            # Check if it looks like an encrypted value (Fernet tokens start with specific prefix)
            # If it's already decrypted (doesn't look encrypted), return as-is
            if not value.startswith('gAAAAAB'):
                # Doesn't look like a Fernet token, assume it's already plaintext
                return value
            
            # Try to decrypt
            try:
                decrypted_bytes = self._fernet.decrypt(value.encode('utf-8'))
                return decrypted_bytes.decode('utf-8')
            except InvalidToken:
                # Not encrypted, return as-is
                return value
            except Exception as e:
                _logger.warning(f"Error decrypting in to_python: {e}")
                return value
        
        # Convert non-string to string
        return str(value)

