"""
Tests for the @validate_payload decorator.
"""
import json
from django.test import TestCase, RequestFactory
from django.contrib.auth import get_user_model
from pydantic import BaseModel, Field, ValidationError
from typing import Optional, List

from api.validation.feature_updates import validate_payload, validate_pydantic_model

User = get_user_model()


# Test models for decorator testing
class SimplePayload(BaseModel):
    """Simple test model."""
    name: str
    value: int


class OptionalPayload(BaseModel):
    """Model with optional fields."""
    required_field: str
    optional_field: Optional[str] = None
    optional_list: Optional[List[str]] = None


class StrictPayload(BaseModel):
    """Model that forbids extra fields."""
    name: str
    
    class Config:
        extra = 'forbid'


class TestValidatePayloadDecorator(TestCase):
    """Test the @validate_payload decorator."""

    def setUp(self):
        """Set up test fixtures."""
        self.factory = RequestFactory()
        self.user = User.objects.create_user(
            email='test@example.com',
            password='testpass123',
            username='testuser'
        )

    def test_decorator_valid_json(self):
        """Test decorator with valid JSON."""
        @validate_payload(SimplePayload)
        def test_view(request, validated_data):
            return validated_data
        
        request = self.factory.post(
            '/test/',
            data=json.dumps({'name': 'test', 'value': 42}),
            content_type='application/json'
        )
        request.user = self.user
        
        result = test_view(request)
        self.assertEqual(result['name'], 'test')
        self.assertEqual(result['value'], 42)

    def test_decorator_invalid_json(self):
        """Test decorator with invalid JSON."""
        @validate_payload(SimplePayload)
        def test_view(request, validated_data):
            return validated_data
        
        request = self.factory.post(
            '/test/',
            data='invalid json',
            content_type='application/json'
        )
        request.user = self.user
        
        response = test_view(request)
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('Invalid JSON', data.get('error', ''))

    def test_decorator_validation_error(self):
        """Test decorator with validation error."""
        @validate_payload(SimplePayload)
        def test_view(request, validated_data):
            return validated_data
        
        request = self.factory.post(
            '/test/',
            data=json.dumps({'name': 'test', 'value': 'not-an-int'}),
            content_type='application/json'
        )
        request.user = self.user
        
        response = test_view(request)
        self.assertEqual(response.status_code, 400)
        data = json.loads(response.content)
        self.assertIn('Invalid request format', data.get('error', ''))

    def test_decorator_extra_fields_forbidden(self):
        """Test decorator rejects extra fields when configured."""
        @validate_payload(StrictPayload)
        def test_view(request, validated_data):
            return validated_data
        
        request = self.factory.post(
            '/test/',
            data=json.dumps({'name': 'test', 'extra_field': 'should_fail'}),
            content_type='application/json'
        )
        request.user = self.user
        
        response = test_view(request)
        self.assertEqual(response.status_code, 400)

    def test_decorator_empty_body_not_allowed(self):
        """Test decorator rejects empty body when not allowed."""
        @validate_payload(SimplePayload)
        def test_view(request, validated_data):
            return validated_data
        
        request = self.factory.post('/test/')
        request.user = self.user
        
        response = test_view(request)
        self.assertEqual(response.status_code, 400)

    def test_decorator_empty_body_allowed(self):
        """Test decorator accepts empty body when allowed."""
        @validate_payload(OptionalPayload, allow_empty=True)
        def test_view(request, validated_data):
            return validated_data
        
        request = self.factory.post('/test/')
        request.user = self.user
        
        result = test_view(request)
        # Should get empty dict with defaults applied
        self.assertEqual(result, {})

    def test_decorator_optional_fields(self):
        """Test decorator with optional fields."""
        @validate_payload(OptionalPayload)
        def test_view(request, validated_data):
            return validated_data
        
        request = self.factory.post(
            '/test/',
            data=json.dumps({'required_field': 'test'}),
            content_type='application/json'
        )
        request.user = self.user
        
        result = test_view(request)
        self.assertEqual(result['required_field'], 'test')
        self.assertNotIn('optional_field', result)
        self.assertNotIn('optional_list', result)

    def test_decorator_with_url_kwargs(self):
        """Test decorator passes through URL kwargs."""
        @validate_payload(SimplePayload)
        def test_view(request, item_id, validated_data):
            return {'item_id': item_id, 'data': validated_data}
        
        request = self.factory.post(
            '/test/123/',
            data=json.dumps({'name': 'test', 'value': 42}),
            content_type='application/json'
        )
        request.user = self.user
        
        result = test_view(request, item_id=123)
        self.assertEqual(result['item_id'], 123)
        self.assertEqual(result['data']['name'], 'test')

    def test_decorator_boundary_string(self):
        """Test decorator handles Django Test Client boundary markers."""
        @validate_payload(SimplePayload, allow_empty=True)
        def test_view(request, validated_data):
            return validated_data
        
        # Simulate Django Test Client sending boundary marker
        request = self.factory.post(
            '/test/',
            data=b'--BoUnDaRyStRiNg--\r\n',
            content_type='multipart/form-data'
        )
        request.user = self.user
        
        result = test_view(request)
        # Should treat as empty and return empty dict
        self.assertEqual(result, {})


class TestValidatePydanticModel(TestCase):
    """Test the validate_pydantic_model function."""

    def test_validate_simple_model(self):
        """Test validating a simple model."""
        data = {'name': 'test', 'value': 42}
        result = validate_pydantic_model(SimplePayload, data)
        self.assertEqual(result['name'], 'test')
        self.assertEqual(result['value'], 42)

    def test_validate_model_with_defaults(self):
        """Test validation fills in defaults."""
        data = {'required_field': 'test'}
        result = validate_pydantic_model(OptionalPayload, data)
        self.assertEqual(result['required_field'], 'test')
        # Optional fields with None default should not be in result (exclude_none=True)
        self.assertNotIn('optional_field', result)

    def test_validate_model_raises_on_invalid(self):
        """Test validation raises ValidationError on invalid data."""
        data = {'name': 'test', 'value': 'not-an-int'}
        with self.assertRaises(ValidationError):
            validate_pydantic_model(SimplePayload, data)

    def test_validate_model_missing_required(self):
        """Test validation raises on missing required field."""
        data = {'optional_field': 'test'}
        with self.assertRaises(ValidationError):
            validate_pydantic_model(OptionalPayload, data)

    def test_validate_model_exclude_none(self):
        """Test that None values are excluded from result."""
        data = {'required_field': 'test', 'optional_field': None}
        result = validate_pydantic_model(OptionalPayload, data)
        self.assertNotIn('optional_field', result)
        self.assertNotIn('optional_list', result)


