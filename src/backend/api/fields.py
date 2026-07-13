"""
Custom Django model fields with encryption support.
"""
import base64
from django.db import models
from cryptography.fernet import Fernet, InvalidToken
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from geo_lib.logging.console import get_tagged_logger
from website.settings_utils import get_required_setting

_logger = get_tagged_logger('EncryptedField')

# Module-level Fernet instance (initialized once, reused for all field instances)
_fernet_instance = None

# Fixed salt for key derivation (deterministic per SECRET_KEY; do not change or existing data won't decrypt)
_FERNET_KDF_SALT = b"geovault-fernet-v1"
_FERNET_KDF_ITERATIONS = 600000

# Fernet token structure (per the spec): 1-byte version (0x80) + 8-byte big-endian timestamp +
# 16-byte IV + ciphertext (padded to a multiple of 16 bytes, minimum one block) + 32-byte HMAC.
# So the minimum decoded length is 1 + 8 + 16 + 16 + 32 = 73 bytes, and every valid length beyond
# that differs by a whole 16-byte AES block.
_FERNET_VERSION_BYTE = 0x80
_FERNET_MIN_DECODED_LENGTH = 73
_FERNET_CIPHERTEXT_BLOCK_SIZE = 16


def _looks_like_fernet_token(value: str) -> bool:
    """
    Precise structural check for whether a string is a plausible Fernet token, rather than the
    loose 'starts with gAAAAAB' string-prefix guess: base64-urlsafe-decode it and verify the
    decoded bytes match Fernet's fixed layout (version byte, minimum length, block-aligned
    ciphertext). This still can't prove the token decrypts successfully (wrong key/HMAC mismatch
    is a separate, expected failure mode handled by the InvalidToken branches below) but it
    correctly rejects plaintext that merely happens to start with the same characters.
    """
    if not value:
        return False
    try:
        decoded = base64.urlsafe_b64decode(value.encode('ascii') + b'=' * (-len(value) % 4))
    except (ValueError, TypeError, UnicodeEncodeError, base64.binascii.Error):
        return False
    if len(decoded) < _FERNET_MIN_DECODED_LENGTH or decoded[0] != _FERNET_VERSION_BYTE:
        return False
    return (len(decoded) - _FERNET_MIN_DECODED_LENGTH) % _FERNET_CIPHERTEXT_BLOCK_SIZE == 0


def _get_fernet_instance():
    """
    Get or create the module-level Fernet instance.
    Derives encryption key from Django's SECRET_KEY using PBKDF2-HMAC-SHA256.

    Returns:
        Fernet instance for encryption/decryption
    """
    global _fernet_instance

    if _fernet_instance is None:
        secret_key = get_required_setting('SECRET_KEY')
        kdf = PBKDF2HMAC(
            algorithm=hashes.SHA256(),
            length=32,
            salt=_FERNET_KDF_SALT,
            iterations=_FERNET_KDF_ITERATIONS,
        )
        key_bytes = kdf.derive(secret_key.encode())
        fernet_key = base64.urlsafe_b64encode(key_bytes)
        _fernet_instance = Fernet(fernet_key)
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
        except InvalidToken:
            # This could happen if:
            # - Data was encrypted with a different key
            # - Data is corrupted
            # - Data is plaintext (from before encryption was added)
            # Logged at warning level with a traceback rather than silently returning the
            # ciphertext as a plain string, since a wrong-key/corrupted-data case looks
            # identical to the harmless pre-encryption-plaintext case otherwise.
            _logger.warning(
                "Failed to decrypt field value (InvalidToken). This may be plaintext data from "
                "before encryption was added, or data encrypted with a different key.",
                exc_info=True,
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
            # Check if it looks like an encrypted value via the Fernet token structure (version
            # byte, minimum length, block-aligned ciphertext) rather than a loose string-prefix
            # guess. If it doesn't look encrypted, it's already decrypted plaintext; return as-is.
            if not _looks_like_fernet_token(value):
                return value
            
            # Try to decrypt
            try:
                decrypted_bytes = self._fernet.decrypt(value.encode('utf-8'))
                return decrypted_bytes.decode('utf-8')
            except InvalidToken:
                # Structurally looked like a Fernet token but failed to decrypt (wrong key or
                # corrupted data) - log so this is visible, then return as-is like from_db_value.
                _logger.warning(
                    "Failed to decrypt field value in to_python() (InvalidToken) despite the "
                    "value looking like a Fernet token; returning it unchanged.",
                    exc_info=True,
                )
                return value
            except Exception as e:
                _logger.error(f"Error decrypting in to_python: {e}", exc_info=True)
                return value
        
        # Convert non-string to string
        return str(value)

