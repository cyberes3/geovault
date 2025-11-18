#!/usr/bin/env python3
"""
Generate icon registry JSON file from caltopo icons directory.

This script scans the caltopo icons directory and generates a JSON registry
with icon metadata organized by category (Points, Letters, Recreation).
"""

import json
import re
from pathlib import Path


def natural_sort_key(text):
    """
    Generate a sort key for natural sorting (handles numbers correctly).
    
    Example: 'circle-10' sorts after 'circle-2' instead of before it.
    
    Args:
        text: String to generate sort key for
    
    Returns:
        List of strings and integers for natural sorting
    """
    def convert(text_part):
        return int(text_part) if text_part.isdigit() else text_part.lower()
    
    return [convert(c) for c in re.split(r'(\d+)', text)]

# Style suffixes in order of preference
STYLE_SUFFIXES = [
    'circle', 'square', 'hexagon', 'diamond', 'pentagon', 
    'triangle', 'placemark', 'square-rounded'
]

# Point marker base names (basic geometric shapes and markers)
POINT_MARKERS = {
    'dot', 'circle', 'target', 'arrow', 'triangle', 'pin', 'flag',
    'point', 'marker', 'placemark'
}

# Letter/number patterns
LETTER_PATTERN = re.compile(r'^(circle-[a-z0-9]+|t_[A-Z0-9]+|[A-Z0-9]\.png)$')


def extract_style_and_base(filename):
    """
    Extract style suffix and base name from filename.
    
    Returns:
        tuple: (base_name, style) where style is 'standard' if no suffix
    """
    name_without_ext = filename.replace('.png', '')
    
    # Check for style suffixes
    for suffix in STYLE_SUFFIXES:
        if name_without_ext.endswith(f'_{suffix}'):
            base_name = name_without_ext[:-len(f'_{suffix}')]
            return base_name, suffix
    
    # No style suffix - it's a standard icon
    return name_without_ext, 'standard'


def categorize_icon(filename, base_name):
    """
    Categorize icon into Points, Letters, or Recreation.
    
    Returns:
        str: 'points', 'letters', or 'recreation'
    """
    # Check if it's a letter/number icon
    if LETTER_PATTERN.match(filename.replace('.png', '')):
        return 'letters'
    
    # Check if it's a point marker
    if base_name.lower() in POINT_MARKERS:
        return 'points'
    
    # Check if base name starts with point marker patterns
    base_lower = base_name.lower()
    for marker in POINT_MARKERS:
        if base_lower.startswith(marker) or base_lower.endswith(marker):
            return 'points'
    
    # Everything else is recreation
    return 'recreation'


def generate_registry(icons_dir, output_file):
    """
    Generate icon registry JSON file.
    
    Args:
        icons_dir: Path to caltopo icons directory
        output_file: Path to output JSON file
    """
    icons_path = Path(icons_dir)
    if not icons_path.exists():
        raise FileNotFoundError(f"Icons directory not found: {icons_dir}")
    
    # Scan for PNG files
    icon_files = sorted(icons_path.glob('*.png'))
    
    if not icon_files:
        raise ValueError(f"No PNG files found in {icons_dir}")
    
    registry = {
        'points': [],
        'letters': [],
        'recreation': []
    }
    
    # Process each icon file
    for icon_file in icon_files:
        filename = icon_file.name
        base_name, style = extract_style_and_base(filename)
        category = categorize_icon(filename, base_name)
        
        # Use icon route format: /api/data/icons/caltopo/{filename}
        url = f"/api/data/icons/caltopo/{filename}"
        
        icon_entry = {
            'url': url,
            'filename': filename.replace('.png', ''),
            'style': style,
            'base_name': base_name
        }
        
        registry[category].append(icon_entry)
    
    # Sort each category using natural sort
    for category in registry:
        registry[category].sort(key=lambda x: (natural_sort_key(x['base_name']), x['style']))
    
    # Write JSON file
    output_path = Path(output_file)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    with open(output_path, 'w') as f:
        json.dump(registry, f, indent=2)
    
    print(f"Generated icon registry with {len(icon_files)} icons")
    print(f"  Points: {len(registry['points'])}")
    print(f"  Letters: {len(registry['letters'])}")
    print(f"  Recreation: {len(registry['recreation'])}")
    print(f"Output: {output_path}")


if __name__ == '__main__':
    import sys
    
    # Get script directory (backend root)
    script_dir = Path(__file__).parent
    icons_dir = script_dir / 'assets' / 'icons' / 'caltopo'
    output_file = script_dir / 'assets' / 'icons' / 'icon-registry.json'
    
    try:
        generate_registry(icons_dir, output_file)
        sys.exit(0)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

