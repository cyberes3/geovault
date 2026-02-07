"""
SSRF protection for outbound HTTP fetches (e.g. remote icon URLs from KML/KMZ).
Blocks private, loopback, link-local, and reserved IP ranges.
"""

import ipaddress
import socket
from urllib.parse import urlparse

ALLOWED_SCHEMES = ("http", "https")


def is_url_safe_for_fetch(url: str) -> bool:
    """
    Return True if the URL is safe to fetch (no SSRF to internal/reserved IPs).
    Allows only http/https; resolves host and blocks private/loopback/link-local/reserved.
    """
    if not url or not url.strip():
        return False
    try:
        parsed = urlparse(url)
        if parsed.scheme not in ALLOWED_SCHEMES:
            return False
        hostname = (parsed.hostname or "").strip()
        if not hostname:
            return False
        port = parsed.port
        if port is None:
            port = 443 if parsed.scheme == "https" else 80
        # Resolve to all addresses (IPv4 and IPv6)
        for family, _, _, _, sockaddr in socket.getaddrinfo(
            hostname, port, socket.AF_UNSPEC, socket.SOCK_STREAM
        ):
            if family == socket.AF_INET:
                ip_str = sockaddr[0]
                try:
                    ip = ipaddress.IPv4Address(ip_str)
                except ValueError:
                    return False
                if (
                    ip.is_private
                    or ip.is_loopback
                    or ip.is_link_local
                    or ip.is_reserved
                    or ip_str == "0.0.0.0"
                ):
                    return False
            elif family == socket.AF_INET6:
                ip_str = sockaddr[0]
                try:
                    ip = ipaddress.IPv6Address(ip_str)
                except ValueError:
                    return False
                if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved:
                    return False
        return True
    except (socket.gaierror, ValueError, OSError):
        return False
