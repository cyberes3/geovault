"""
Secure filename and path helpers for request-derived input used on the filesystem.
Allows spaces (unlike werkzeug's secure_filename which uses underscores).
"""

import os
import re
import unicodedata
from pathlib import Path

# Allow alphanumeric, space, underscore, dot, hyphen (same as werkzeug but with space)
_filename_ascii_allow_spaces_re = re.compile(r"[^A-Za-z0-9 _.\-]")

# https://chrisdenton.github.io/omnipath/Special%20Dos%20Device%20Names.html
_windows_device_files = {
    "AUX",
    "CON",
    "CONIN$",
    "CONOUT$",
    *(f"COM{c}" for c in "123456789¹²³"),
    *(f"LPT{c}" for c in "123456789¹²³"),
    "NUL",
    "PRN",
}


def secure_filename(filename: str) -> str:
    """Secure filename for filesystem; allows spaces (unlike werkzeug which uses underscores)."""
    filename = unicodedata.normalize("NFKD", filename)
    filename = filename.encode("ascii", "ignore").decode("ascii")

    for sep in os.sep, os.path.altsep:
        if sep:
            filename = filename.replace(sep, " ")

    filename = str(_filename_ascii_allow_spaces_re.sub("", filename)).strip("._ ")
    filename = " ".join(filename.split())  # collapse internal whitespace to single space

    if (
        os.name == "nt"
        and filename
        and filename.split(".")[0].upper() in _windows_device_files
    ):
        filename = f"_{filename}"

    return filename


def secure_path(path: str) -> str:
    """
    Sanitize a multi-segment path (e.g. from URL) for safe use under a base directory.
    Splits on /, applies secure_filename to each segment, rejoins.
    """
    if not path or not path.strip():
        return "_"
    segments = [s for s in path.split("/") if s.strip()]
    if not segments:
        return "_"
    safe = "/".join(secure_filename(seg) for seg in segments)
    return safe if safe else "_"


def is_path_under_base(path: Path, base: Path) -> bool:
    """
    Return True if path is under base (after resolving both). Use to prevent path traversal
    when serving or reading files from request-derived paths.
    """
    try:
        resolved_path = path.resolve()
        resolved_base = base.resolve()
        try:
            return resolved_path.is_relative_to(resolved_base)
        except AttributeError:
            try:
                resolved_path.relative_to(resolved_base)
                return True
            except ValueError:
                return False
    except (ValueError, RuntimeError):
        return False
