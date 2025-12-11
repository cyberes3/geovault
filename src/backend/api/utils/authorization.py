"""
Authorization utilities for consistent user ownership checks.

This module provides utilities to check if database objects belong to the authenticated user,
reducing code duplication and ensuring consistent error handling across API views.
"""

from typing import Type, TypeVar

from django.contrib.auth import get_user_model
from django.db import models
from django.http import Http404

User = get_user_model()
T = TypeVar('T', bound=models.Model)


def get_object_or_404_for_user(model: Type[T], user: User, **filters) -> T:
    """
    Get an object owned by the specified user or raise Http404.
    
    This is a user-aware version of Django's get_object_or_404 that automatically
    filters by user ownership and raises Http404 if the object doesn't exist or
    doesn't belong to the user.
    
    Args:
        model: Django model class to query
        user: The user who should own the object
        **filters: Additional filter parameters (e.g., id=123, share_id='abc')
        
    Returns:
        The model instance if found and owned by the user
        
    Raises:
        Http404: If the object doesn't exist or doesn't belong to the user
        
    Examples:
        # Get a feature by ID for the current user
        feature = get_object_or_404_for_user(FeatureStore, request.user, id=feature_id)
        
        # Get a collection by ID for the current user
        collection = get_object_or_404_for_user(Collection, request.user, id=collection_id)
        
        # Get an import queue item by ID for the current user
        item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)
        
    Note:
        Views using this function should catch Http404 and convert to appropriate
        JSON responses using not_found_response() or use the @handle_404 decorator.
    """
    try:
        return model.objects.get(user=user, **filters)
    except model.DoesNotExist:
        raise Http404(f"{model.__name__} not found or access denied")
