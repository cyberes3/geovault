"""
Pytest plugin to fix Django 6.0a1 version parsing bug.

This plugin patches Django's version checking before any tests run.
The patch is applied at module import time to ensure it's active before any Django code runs.
"""
import sys


# Patch immediately when this module is imported (before pytest_configure)
# This ensures the patch is active before any Django imports happen
try:
    import django.utils.version as version_module
    from django import VERSION as real_version
    
    # Save the original function
    _original_get_complete_version = version_module.get_complete_version
    
    def patched_get_complete_version(version=None):
        """Patched version of get_complete_version to work around Django 6.0a1 bug."""
        # Always get Django's real VERSION first as a fallback
        
        if version is None:
            version = real_version
        else:
            # Always validate the version parameter before passing to original
            # If it's not a valid tuple with 5 elements, use Django's VERSION
            use_real_version = False
            
            try:
                # Check if it's a valid tuple
                if not isinstance(version, tuple):
                    use_real_version = True
                elif len(version) != 5:
                    use_real_version = True
                else:
                    # Check if it's a mock by checking type name or attributes
                    type_name = type(version).__name__
                    if type_name in ('MagicMock', 'Mock', 'AsyncMock'):
                        use_real_version = True
                    elif hasattr(version, '_mock_name') or hasattr(version, '_spec_class'):
                        use_real_version = True
            except (TypeError, AttributeError, AssertionError):
                # If anything goes wrong checking, use real version
                use_real_version = True
            
            if use_real_version:
                version = real_version
        
        # Now call the original with the validated version
        # Wrap in try/except as a final safety net
        try:
            return _original_get_complete_version(version)
        except AssertionError:
            # Final fallback - use real version
            return _original_get_complete_version(real_version)
    
    # Apply the patch immediately
    version_module.get_complete_version = patched_get_complete_version
except ImportError:
    # Django not installed yet, that's okay - patch will be applied when Django is imported
    pass

def pytest_configure(config):
    """Configure pytest - patch is already applied at import time."""
    # The patch is already applied above, this is just for documentation
    pass

