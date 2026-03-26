"""
Version utility functions for getting git commit hash.
"""
import subprocess
from pathlib import Path


def get_git_commit_hash() -> str:
    """
    Get the current git commit hash (short version).
    
    Returns:
        Short commit hash string, or 'unknown' if git is not available or not in a git repo.
    """
    try:
        # Get the backend directory (where this file is located)
        backend_dir = Path(__file__).parent.parent.parent.parent
        # Get the project root (parent of backend)
        project_root = backend_dir.parent
        
        # Run git command to get short commit hash
        result = subprocess.run(
            ['git', 'rev-parse', '--short=10', 'HEAD'],
            cwd=project_root,
            capture_output=True,
            text=True,
            timeout=1,
            check=False
        )
        
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()
        else:
            return 'unknown'
    except (subprocess.TimeoutExpired, FileNotFoundError, Exception):
        return 'unknown'


def get_user_agent() -> str:
    """
    Get the user agent string for HTTP requests.
    
    Returns:
        User agent string in format 'GeoVault/[commit hash]'
    """
    commit_hash = get_git_commit_hash()
    return f'GeoVault/{commit_hash}'

