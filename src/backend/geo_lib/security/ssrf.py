"""
SSRF protection for outbound HTTP fetches (e.g. remote icon URLs from KML/KMZ).
Blocks private, loopback, link-local, and reserved IP ranges.

Two layers are provided:

- `is_url_safe_for_fetch()`: a cheap upfront check (e.g. to fail fast with a clear log message,
  or to filter URLs that will only ever be handed to a browser/client, not fetched by us).
- `SafeHTTPHandler`/`SafeHTTPSHandler` (via `build_ssrf_safe_opener()`): actually perform the
  fetch. Their connections resolve the target host and validate the resolved address *inside*
  `connect()`, immediately before opening the socket, and connect directly to the exact address
  that was just validated. A separate "check URL, then connect() re-resolves the hostname"
  sequence leaves a TOCTOU window: a malicious DNS server can answer the check with a public IP
  and the real connection (a DNS lookup later) with an internal one (DNS rebinding). Folding the
  validation and connection into one atomic step removes that window for any code path that
  performs a real server-side fetch of an untrusted URL.
"""

import http.client
import ipaddress
import socket
import urllib.request
from dataclasses import dataclass
from typing import Optional
from urllib.error import URLError
from urllib.parse import urlparse

ALLOWED_SCHEMES = ("http", "https")


class SSRFBlockedError(URLError):
    """Raised when a connection target fails SSRF validation at connect time."""


def _is_safe_ip(ip_str: str, family: int) -> bool:
    """True if the resolved address is not private/loopback/link-local/reserved."""
    try:
        if family == socket.AF_INET:
            ip = ipaddress.IPv4Address(ip_str)
            if ip_str == "0.0.0.0":
                return False
        elif family == socket.AF_INET6:
            ip = ipaddress.IPv6Address(ip_str)
        else:
            return False
    except ValueError:
        return False
    return not (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved)


def resolve_safe_ip(hostname: str, port: int) -> Optional[str]:
    """
    Resolve `hostname` and return one safe IP to connect to, or None if the hostname is
    unresolvable or any resolved address is private/loopback/link-local/reserved.

    Rejecting on *any* unsafe resolved address (not just the first) keeps multi-homed hostnames
    that resolve to both a public and an internal address from slipping through.
    """
    if not hostname:
        return None
    try:
        addrs = socket.getaddrinfo(hostname, port, socket.AF_UNSPEC, socket.SOCK_STREAM)
    except (socket.gaierror, OSError):
        return None
    if not addrs:
        return None
    pinned_ip = None
    for family, _, _, _, sockaddr in addrs:
        ip_str = sockaddr[0]
        if not _is_safe_ip(ip_str, family):
            return None
        if pinned_ip is None:
            pinned_ip = ip_str
    return pinned_ip


def is_url_safe_for_fetch(url: str) -> bool:
    """
    Return True if the URL is safe to fetch (no SSRF to internal/reserved IPs).
    Allows only http/https; resolves host and blocks private/loopback/link-local/reserved.

    This is a point-in-time check. Code that actually performs the fetch should use
    `build_ssrf_safe_opener()` instead of trusting this check alone, since a real connect()
    happening after this check is a separate DNS resolution and reopens the rebinding window.
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
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        return resolve_safe_ip(hostname, port) is not None
    except (socket.gaierror, ValueError, OSError):
        return False


class _SSRFSafeConnectionMixin:
    """Shared connect-time SSRF validation for the HTTP/HTTPS connection classes below."""

    def _connect_to_safe_ip(self) -> socket.socket:
        pinned_ip = resolve_safe_ip(self.host, self.port)
        if pinned_ip is None:
            raise SSRFBlockedError(f"Host {self.host!r} is not allowed (SSRF protection)")
        sock = self._create_connection((pinned_ip, self.port), self.timeout, self.source_address)
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
        return sock


class SafeHTTPConnection(_SSRFSafeConnectionMixin, http.client.HTTPConnection):
    """HTTPConnection that validates and pins its connect() target atomically."""

    def connect(self):
        self.sock = self._connect_to_safe_ip()
        if self._tunnel_host:
            self._tunnel()


class SafeHTTPSConnection(_SSRFSafeConnectionMixin, http.client.HTTPSConnection):
    """HTTPSConnection that validates and pins its connect() target atomically.

    TLS is unaffected: `wrap_socket` still uses the original hostname for SNI and certificate
    verification, so this only changes which IP the raw TCP connection is made to.
    """

    def connect(self):
        sock = self._connect_to_safe_ip()
        if self._tunnel_host:
            self.sock = sock
            self._tunnel()
            sock = self.sock
        self.sock = self._context.wrap_socket(sock, server_hostname=self._tunnel_host or self.host)


class SafeHTTPHandler(urllib.request.HTTPHandler):
    def http_open(self, req):
        return self.do_open(SafeHTTPConnection, req)


class SafeHTTPSHandler(urllib.request.HTTPSHandler):
    def https_open(self, req):
        return self.do_open(SafeHTTPSConnection, req, context=self._context)


_MAX_REDIRECTS = 5


class SSRFSafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Fails fast on an unsafe redirect target (clearer error than waiting for connect() to
    reject it) and enforces a redirect count limit."""

    def __init__(self, max_redirects: int = _MAX_REDIRECTS):
        self.max_redirects = max_redirects
        self.redirect_count = 0

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if self.redirect_count >= self.max_redirects:
            raise URLError("Too many redirects")
        from urllib.parse import urljoin

        redirect_url = urljoin(req.full_url, newurl)
        if not is_url_safe_for_fetch(redirect_url):
            raise SSRFBlockedError("Redirect target not allowed (SSRF)")
        self.redirect_count += 1
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def build_ssrf_safe_opener() -> urllib.request.OpenerDirector:
    """
    Build a urllib opener for fetching untrusted, request-influenced URLs (e.g. icon URLs from
    imported KML/KMZ files). Every connection it makes is validated and pinned atomically at
    connect() time; redirects are limited and pre-validated for a clean failure message.
    """
    return urllib.request.build_opener(SafeHTTPHandler(), SafeHTTPSHandler(), SSRFSafeRedirectHandler())
