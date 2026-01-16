# ==============================================================================
# GeoVault Extension Manifest
# ==============================================================================
# This file is the primary entry point for the GeoVault extension system.
# The platform discovers extensions by scanning the extensions directory and 
# looking for files named 'manifest.py'.

# The unique internal name of the extension. Used for folder naming and URL scoping.
# Note: Use snake_case for maximum compatibility.
name = "example_extension"

# Versioning follows Semantic Versioning (semver.org)
version = "1.0.0"

# A brief description shown in the extension management interface.
description = "A demonstration extension with CRUD and settings."

# Icon for the extension
# Example 1: Heroicon (active)
# icon = "CogIcon"
# Example 2: SVG file (commented out)
# icon = "icon.svg"
# Example 3: Inline SVG (commented out)
icon = '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2" fill="none"/><path d="M12 2 L12 8 M12 16 L12 22 M2 12 L8 12 M16 12 L22 12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="2" fill="none"/></svg>'

# Controls whether the extension is loaded automatically.
# Since this is an example, we disable it by default.
enabled_by_default = False
