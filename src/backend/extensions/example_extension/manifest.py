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

# Controls whether the extension is loaded automatically.
# Since this is an example, we disable it by default.
enabled_by_default = False
