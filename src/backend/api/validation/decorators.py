"""
Shared request-payload validation decorator, used across every endpoint that accepts a
JSON body (feature updates, bulk operations, collections, sharing, imports, etc.).

Pydantic payload models themselves live under `api.validation.payloads`, kept separate
from this decorator so each payload module only pulls in what it needs.
"""

import json
from functools import wraps
from typing import Any, Dict, Type

from pydantic import BaseModel, ValidationError

from api.utils.responses import error_response


def validate_pydantic_model(model_class: Type[BaseModel], data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Generic validation function for any Pydantic model.

    Args:
        model_class: The Pydantic model class to validate against
        data: Dictionary from request body to validate

    Returns:
        Validated and normalized dictionary

    Raises:
        ValidationError: If validation fails
    """
    validated_model = model_class.model_validate(data)
    return validated_model.model_dump(mode='json', exclude_none=True)


def validate_payload(model_class: Type[BaseModel], allow_empty: bool = False):
    """
    Decorator that validates request payload against a Pydantic model.

    Automatically handles JSON parsing and validation, injecting validated_data
    into the decorated function.

    Args:
        model_class: The Pydantic model class to validate against
        allow_empty: If True, allows empty request body (returns empty dict)

    Usage:
        @validate_payload(CollectionCreatePayload)
        def create_collection(request, validated_data):
            # validated_data is already parsed and validated
            name = validated_data['name']
            ...

        @validate_payload(ImportToFeaturestorePayload, allow_empty=True)
        def import_to_featurestore(request, validated_data):
            # Empty body is allowed, validated_data will be {}
            ...
    """

    def decorator(view_func):
        @wraps(view_func)
        def wrapper(request, *args, **kwargs):
            # Handle empty body - check for truly empty or just boundary markers
            # Django Test Client may send boundary markers even with no actual content
            body = request.body
            has_body = bool(body and len(body) > 0)

            # If there's a body, try to parse it as JSON
            # If it fails (e.g., it's just boundary markers), treat as empty
            if has_body:
                try:
                    data = json.loads(body)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    # Check if this looks like intentional JSON (has content-type header or starts with { or [)
                    content_type = request.META.get('CONTENT_TYPE', '')
                    body_str = body.decode('utf-8', errors='ignore') if isinstance(body, bytes) else str(body)
                    looks_like_json = 'json' in content_type.lower() or body_str.strip().startswith(('{', '['))

                    if looks_like_json:
                        # Intentional JSON that failed to parse - return error
                        return error_response('Invalid JSON in request body', code=400)
                    elif allow_empty:
                        # Not JSON, treat as empty if allowed
                        kwargs['validated_data'] = {}
                        return view_func(request, *args, **kwargs)
                    else:
                        return error_response('Invalid JSON in request body', code=400)

                # Valid JSON - validate against Pydantic model
                try:
                    validated_data = validate_pydantic_model(model_class, data)
                    kwargs['validated_data'] = validated_data
                    return view_func(request, *args, **kwargs)
                except ValidationError as e:
                    # Return more detailed error message
                    error_messages = [f"{'.'.join(str(loc) for loc in err['loc'])}: {err['msg']}" for err in e.errors()]
                    error_msg = 'Invalid request format' + (f' - {"; ".join(error_messages)}' if error_messages else '')
                    return error_response(error_msg, code=400)
            else:
                # No body at all
                if allow_empty:
                    kwargs['validated_data'] = {}
                    return view_func(request, *args, **kwargs)
                else:
                    return error_response('Request body is required', code=400)

        return wrapper

    return decorator
