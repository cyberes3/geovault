"""
Standardized API response utilities using Pydantic models.
Provides consistent response formats across all API endpoints.
"""

from typing import Any, Dict, Optional
from pydantic import BaseModel, Field
from django.http import JsonResponse


class ErrorResponse(BaseModel):
    """Standardized error response model."""
    error: str = Field(..., description="Human-readable error message")
    code: int = Field(..., description="HTTP status code")
    details: Optional[Dict[str, Any]] = Field(default=None, description="Optional detailed error information")

    class Config:
        json_schema_extra = {
            "example": {
                "error": "Invalid input data",
                "code": 400,
                "details": {"field": "name", "issue": "required"}
            }
        }


class SuccessResponse(BaseModel):
    """Standardized success response model with optional message."""
    msg: Optional[str] = Field(default=None, description="Success message")
    data: Optional[Dict[str, Any]] = Field(default=None, description="Response data")

    class Config:
        json_schema_extra = {
            "example": {
                "msg": "Operation completed successfully",
                "data": {"id": 123}
            }
        }


def error_response(
    error_message: str,
    code: int = 400,
    details: Optional[Dict[str, Any]] = None
) -> JsonResponse:
    """
    Create a standardized error response.
    
    Args:
        error_message: Human-readable error message
        code: HTTP status code (default: 400)
        details: Optional dictionary with detailed error information
        
    Returns:
        JsonResponse with standardized error format
        
    Example:
        return error_response("User not found", code=404)
        return error_response("Validation failed", code=400, details={"field": "email"})
    """
    response = ErrorResponse(error=error_message, code=code, details=details)
    return JsonResponse(response.model_dump(exclude_none=True), status=code)


def success_response(
    data: Optional[Dict[str, Any]] = None,
    message: Optional[str] = None,
    status: int = 200
) -> JsonResponse:
    """
    Create a standardized success response.
    
    Args:
        data: Response data dictionary
        message: Optional success message
        status: HTTP status code (default: 200)
        
    Returns:
        JsonResponse with data
        
    Example:
        return success_response({"user_id": 123})
        return success_response({"count": 5}, message="Items deleted")
    """
    if data is None:
        data = {}
    
    # If message is provided, include it in the response
    if message:
        data = {"msg": message, **data}
    
    return JsonResponse(data, status=status)


def validation_error_response(
    field_errors: Dict[str, str],
    message: str = "Validation failed"
) -> JsonResponse:
    """
    Create a validation error response with field-level errors.
    
    Args:
        field_errors: Dictionary mapping field names to error messages
        message: General validation error message
        
    Returns:
        JsonResponse with validation errors
        
    Example:
        return validation_error_response({
            "email": "Invalid email format",
            "password": "Password too short"
        })
    """
    return error_response(message, code=400, details={"fields": field_errors})


def not_found_response(message: str = "Resource not found") -> JsonResponse:
    """
    Create a 404 not found response.
    
    Args:
        message: Error message
        
    Returns:
        JsonResponse with 404 status
    """
    return error_response(message, code=404)


def unauthorized_response(message: str = "Unauthorized") -> JsonResponse:
    """
    Create a 401 unauthorized response.
    
    Args:
        message: Error message
        
    Returns:
        JsonResponse with 401 status
    """
    return error_response(message, code=401)


def forbidden_response(message: str = "Forbidden") -> JsonResponse:
    """
    Create a 403 forbidden response.
    
    Args:
        message: Error message
        
    Returns:
        JsonResponse with 403 status
    """
    return error_response(message, code=403)


def server_error_response(message: str = "Internal server error") -> JsonResponse:
    """
    Create a 500 internal server error response.
    
    Args:
        message: Error message
        
    Returns:
        JsonResponse with 500 status
    """
    return error_response(message, code=500)

