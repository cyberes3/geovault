"""
Result utilities for import job operations.
Provides standardized success and error result dictionaries.
"""

from typing import Dict, Any


def job_success_result(imported: int = 0, duplicates_skipped=None, **kwargs) -> Dict[str, Any]:
    """
    Create a standardized success result for job operations.
    
    Args:
        imported: Number of features successfully imported
        duplicates_skipped: Either an int count or a dict with skipped duplicate details
        **kwargs: Additional fields to include in the result
        
    Returns:
        Dictionary with success=True and result data
    """
    result = {'success': True, 'imported': imported}
    if duplicates_skipped:
        result['duplicates_skipped'] = duplicates_skipped
    result.update(kwargs)
    return result


def job_error_result(error_message: str, **kwargs) -> Dict[str, Any]:
    """
    Create a standardized error result for job operations.
    
    Args:
        error_message: Human-readable error message
        **kwargs: Additional fields to include in the result
        
    Returns:
        Dictionary with success=False and error message
    """
    result = {'success': False, 'error': error_message}
    result.update(kwargs)
    return result
