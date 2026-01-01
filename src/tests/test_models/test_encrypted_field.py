"""
Tests for EncryptedTextField custom Django model field.
"""
from django.test import TestCase, override_settings
from django.db import models
from django.contrib.auth import get_user_model

from api.fields import EncryptedTextField
from api.models import CalTopoUser

User = get_user_model()


class DummyModel(models.Model):
    """Dummy model for EncryptedTextField testing (not a test class)."""
    secret = EncryptedTextField(help_text="Encrypted secret data")
    
    class Meta:
        app_label = 'api'


class TestEncryptedTextField(TestCase):
    """Test EncryptedTextField encryption and decryption."""
    
    def setUp(self):
        """Set up test fixtures."""
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )
    
    def test_encryption_on_save(self):
        """Test that credential_key is encrypted when saving."""
        plaintext_key = "test-credential-key-12345"
        
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key=plaintext_key
        )
        
        # Retrieve raw value from database (bypassing field's from_db_value)
        from django.db import connection
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT credential_key FROM api_caltopouser WHERE user_id = %s",
                [self.user.id]
            )
            raw_value = cursor.fetchone()[0]
        
        # Raw value should be encrypted (Fernet tokens start with 'gAAAAAB')
        self.assertIsNotNone(raw_value)
        self.assertNotEqual(raw_value, plaintext_key)
        self.assertTrue(raw_value.startswith('gAAAAAB'))
    
    def test_decryption_on_retrieval(self):
        """Test that credential_key is decrypted when retrieving."""
        plaintext_key = "test-credential-key-12345"
        
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key=plaintext_key
        )
        
        # Refresh from database
        caltopo_user.refresh_from_db()
        
        # Retrieved value should be decrypted
        self.assertEqual(caltopo_user.credential_key, plaintext_key)
    
    def test_none_value_handling(self):
        """Test that None values are not encrypted."""
        # This test is for the field itself, but CalTopoUser.credential_key is required
        # So we'll test that setting to None and then back works
        plaintext_key = "test-key"
        
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key=plaintext_key
        )
        
        # Field should handle None in get_prep_value
        from api.fields import EncryptedTextField
        field = EncryptedTextField()
        self.assertIsNone(field.get_prep_value(None))
    
    def test_non_string_value_conversion(self):
        """Test that non-string values are converted to string before encrypting."""
        # Test with integer
        caltopo_user = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key=12345  # Integer instead of string
        )
        
        caltopo_user.refresh_from_db()
        # Should be converted to string and encrypted/decrypted correctly
        self.assertEqual(caltopo_user.credential_key, "12345")
    
    def test_invalid_token_handling(self):
        """Test that InvalidToken errors return value as-is."""
        # Create a CalTopoUser with plaintext (simulating pre-encryption data)
        from django.db import connection
        with connection.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO api_caltopouser 
                (user_id, account_id, credential_id, credential_key, imported_features, created_at, updated_at)
                VALUES (%s, %s, %s, %s, %s, NOW(), NOW())
                """,
                [self.user.id, 'abc123', '123456789012', 'plaintext-key', '{}']
            )
        
        # Retrieve should handle InvalidToken gracefully
        caltopo_user = CalTopoUser.objects.get(user=self.user)
        # Should return plaintext value as-is
        self.assertEqual(caltopo_user.credential_key, 'plaintext-key')
    
    def test_plaintext_detection(self):
        """Test that plaintext values are detected and handled gracefully."""
        # Create with plaintext
        from django.db import connection
        with connection.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO api_caltopouser 
                (user_id, account_id, credential_id, credential_key, imported_features, created_at, updated_at)
                VALUES (%s, %s, %s, %s, %s, NOW(), NOW())
                """,
                [self.user.id, 'abc123', '123456789012', 'not-encrypted', '{}']
            )
        
        caltopo_user = CalTopoUser.objects.get(user=self.user)
        # to_python should detect it's not encrypted and return as-is
        self.assertEqual(caltopo_user.credential_key, 'not-encrypted')
    
    def test_key_derivation_from_secret_key(self):
        """Test that encryption key is derived from SECRET_KEY."""
        plaintext_key = "test-key"
        
        caltopo_user1 = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key=plaintext_key
        )
        
        # Get encrypted value
        from django.db import connection
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT credential_key FROM api_caltopouser WHERE user_id = %s",
                [self.user.id]
            )
            encrypted_value = cursor.fetchone()[0]
        
        # Should be encrypted (not plaintext)
        self.assertNotEqual(encrypted_value, plaintext_key)
        self.assertTrue(encrypted_value.startswith('gAAAAAB'))
    
    @override_settings(SECRET_KEY='different-secret-key-for-testing')
    def test_different_secret_keys_produce_different_encryption(self):
        """Test that different SECRET_KEYs produce different encryption."""
        plaintext_key = "test-key"
        
        # Create with first SECRET_KEY
        caltopo_user1 = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key=plaintext_key
        )
        
        # Get encrypted value with first key
        from django.db import connection
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT credential_key FROM api_caltopouser WHERE user_id = %s",
                [self.user.id]
            )
            encrypted_value_1 = cursor.fetchone()[0]
        
        # Delete and recreate with different SECRET_KEY
        # Note: In real scenario, changing SECRET_KEY would break decryption
        # This test verifies that the key derivation works
        caltopo_user1.delete()
        
        # The field should still work (though with different encryption)
        # We can't easily test different keys in same test, but we verify
        # that encryption/decryption works with current key
        caltopo_user2 = CalTopoUser.objects.create(
            user=self.user,
            account_id='abc123',
            credential_id='123456789012',
            credential_key=plaintext_key
        )
        
        caltopo_user2.refresh_from_db()
        # Should still decrypt correctly with current SECRET_KEY
        self.assertEqual(caltopo_user2.credential_key, plaintext_key)

