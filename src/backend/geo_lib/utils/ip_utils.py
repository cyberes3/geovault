"""
Utility functions for extracting client IP addresses.
Supports getting the real IP when behind a proxy (nginx, etc.).
"""
from allauth.account.models import EmailAddress


def get_client_ip(request_or_scope):
    """
    Extract the client IP address from a Django request or WebSocket scope.
    Checks for proxy headers (X-Forwarded-For, X-Real-IP) to get the real IP.
    
    Args:
        request_or_scope: Django request object or WebSocket scope dict
        
    Returns:
        Client IP address string
    """
    # Handle Django request object
    if hasattr(request_or_scope, 'META'):
        meta = request_or_scope.META
    # Handle WebSocket scope dict
    elif isinstance(request_or_scope, dict):
        meta = request_or_scope.get('headers', {})
        # Convert headers dict to META-like format
        # Headers in scope are typically byte strings: [(b'header-name', b'value'), ...]
        meta_dict = {}
        for header_name, header_value in meta:
            # Convert bytes to string and format like HTTP_HEADER_NAME
            header_key = f"HTTP_{header_name.decode('utf-8').upper().replace('-', '_')}"
            meta_dict[header_key] = header_value.decode('utf-8') if isinstance(header_value, bytes) else header_value
        # Also check for REMOTE_ADDR in client info
        if 'client' in request_or_scope:
            client_info = request_or_scope['client']
            if isinstance(client_info, (list, tuple)) and len(client_info) > 0:
                meta_dict['REMOTE_ADDR'] = client_info[0]
        meta = meta_dict
    else:
        return 'unknown'
    
    # Check for X-Forwarded-For header (most common proxy header)
    x_forwarded_for = meta.get('HTTP_X_FORWARDED_FOR')
    if x_forwarded_for:
        # X-Forwarded-For can contain multiple IPs: "client, proxy1, proxy2"
        # Take the first one (original client IP)
        ip = x_forwarded_for.split(',')[0].strip()
        if ip:
            return ip
    
    # Check for X-Real-IP header (nginx specific)
    x_real_ip = meta.get('HTTP_X_REAL_IP')
    if x_real_ip:
        ip = x_real_ip.strip()
        if ip:
            return ip
    
    # Fall back to REMOTE_ADDR
    return meta.get('REMOTE_ADDR', '127.0.0.1')


def get_user_identifier(request_or_scope):
    """
    Get user identifier (email or ID) from request or scope.
    
    Args:
        request_or_scope: Django request object or WebSocket scope dict
        
    Returns:
        Email if available, otherwise user ID, otherwise 'Anonymous'
    """
    def get_user_email(user):
        """Helper function to get primary email for a user."""
        try:
            email_address = EmailAddress.objects.filter(user=user, primary=True).first()
            if email_address:
                return email_address.email
            else:
                # Fallback to first email if no primary is set
                email_address = EmailAddress.objects.filter(user=user).first()
                if email_address:
                    return email_address.email
        except Exception:
            pass
        return None
    
    # Handle Django request object
    if hasattr(request_or_scope, 'user'):
        user = request_or_scope.user
        if hasattr(user, 'is_authenticated') and user.is_authenticated:
            # Prefer email if available
            email = get_user_email(user)
            if email:
                return email
            elif hasattr(user, 'id'):
                return str(user.id)
        return 'Anonymous'
    
    # Handle WebSocket scope dict
    if isinstance(request_or_scope, dict):
        user = request_or_scope.get('user')
        if user and hasattr(user, 'is_authenticated') and user.is_authenticated:
            email = get_user_email(user)
            if email:
                return email
            elif hasattr(user, 'id'):
                return str(user.id)
        return 'Anonymous'
    
    return 'Anonymous'

