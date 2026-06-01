"""
Version utility functions for getting git commit hash.
"""
import subprocess
from pathlib import Path


def _commit_fragment_from_full(full: str) -> str:
    """First 10 hex chars of full SHA (same as Android versionName suffix)."""
    s = full.strip()
    if not s or s == "unknown":
        return "unknown"
    if len(s) <= 10:
        return s
    return s[:10]


def get_git_commit_hash() -> str:
    """
    Return a 10-character fragment of the current HEAD commit: the first 10 hex
    characters of the full SHA.

    Returns:
        That fragment, or 'unknown' if git is not available or not in a git repo.
    """
    try:
        # Get the backend directory (where this file is located)
        backend_dir = Path(__file__).parent.parent.parent.parent
        # Get the project root (parent of backend)
        project_root = backend_dir.parent

        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=project_root,
            capture_output=True,
            text=True,
            timeout=1,
            check=False,
        )

        if result.returncode == 0 and result.stdout.strip():
            return _commit_fragment_from_full(result.stdout)
        return "unknown"
    except (subprocess.TimeoutExpired, FileNotFoundError, Exception):
        return "unknown"
