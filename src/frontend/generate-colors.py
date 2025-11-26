#!/usr/bin/env python3
"""
Generate color scales for the custom color scheme.
This script generates colors.css file with CSS variables for all color scales.
Tailwind CSS handles the utility classes automatically via tailwind.config.js.
"""

import colorsys
import os
import re

# Base colors will be parsed from root.css
BASE_COLORS = {}

# Color scale numbers
SCALE_NUMBERS = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900]

# Lightness targets for each scale (0-100)
# Adjusted for better perceptual uniformity and more noticeable differences
LIGHTNESS_TARGETS = {
    50: 97,
    100: 93,
    200: 85,
    300: 75,
    400: 65,
    500: None,  # Will use base color's actual lightness
    600: None,  # Will calculate relative to base
    700: None,  # Will calculate relative to base
    800: 18,
    900: 12
}


def hex_to_rgb(hex_color):
    """Convert hex color to RGB tuple."""
    hex_color = hex_color.lstrip('#')
    return tuple(int(hex_color[i:i+2], 16) for i in (0, 2, 4))


def rgb_to_hex(rgb):
    """Convert RGB tuple to hex color."""
    return f"#{rgb[0]:02X}{rgb[1]:02X}{rgb[2]:02X}"


def rgb_to_hsl(rgb):
    """Convert RGB to HSL."""
    r, g, b = [x / 255.0 for x in rgb]
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    return (h * 360, s * 100, l * 100)


def hsl_to_rgb(hsl):
    """Convert HSL to RGB."""
    h, s, l = hsl
    r, g, b = colorsys.hls_to_rgb(h / 360, l / 100, s / 100)
    return (int(r * 255), int(g * 255), int(b * 255))


def parse_root_css(root_css_path):
    """Parse root.css to extract base color values."""
    colors = {}
    with open(root_css_path, 'r') as f:
        content = f.read()
        # Match patterns like --main-blue: #163D8A;
        pattern = r'--main-(\w+):\s*(#[0-9A-Fa-f]{6});'
        matches = re.findall(pattern, content)
        for color_name, hex_value in matches:
            colors[color_name] = hex_value.upper()
    return colors


def generate_color_scale(base_hex, color_name):
    """Generate a color scale from 50 to 900 based on the base color."""
    base_rgb = hex_to_rgb(base_hex)
    base_hsl = rgb_to_hsl(base_rgb)
    base_h, base_s, base_l = base_hsl
    
    # Calculate relative lightness targets for 500, 600, 700, 800, 900 based on base color
    # Ensure good contrast between adjacent shades and progressive darkening
    base_lightness = base_l
    
    # Calculate darker shades relative to base, ensuring they get progressively darker
    # Use more aggressive darkening for better contrast, especially for hover states
    # Target specific lightness values that provide good visual separation
    if base_lightness > 40:
        # For lighter base colors, use more aggressive darkening
        lightness_600 = max(28, base_lightness * 0.75)
        lightness_700 = max(20, base_lightness * 0.50)  # 50% darker for strong contrast
    else:
        # For already dark base colors (like blue), use fixed darker values with good separation
        lightness_600 = max(28, base_lightness - 5)
        lightness_700 = max(18, base_lightness - 20)  # Much darker for clearly visible hover effect
    
    lightness_800 = max(16, lightness_700 * 0.75)  # Darker than 700
    lightness_900 = max(12, lightness_800 * 0.75)  # Darker than 800
    
    relative_targets = {
        500: base_lightness,  # Use actual base color lightness
        600: lightness_600,
        700: lightness_700,
        800: lightness_800,
        900: lightness_900,
    }
    
    scale = {}
    
    for num in SCALE_NUMBERS:
        if num == 500:
            # Use the base color for 500
            scale[num] = base_hex
        else:
            # Get target lightness (either fixed or calculated)
            if num in relative_targets:
                target_lightness = relative_targets[num]
            else:
                target_lightness = LIGHTNESS_TARGETS[num]
            
            # Adjust saturation: keep colors vibrant, especially for lighter shades
            if num < 500:
                # Lighter shades: maintain high saturation for vibrancy
                # Scale from 60% to 95% of base saturation for more vibrant colors
                saturation = max(40, base_s * (0.6 + (num / 500) * 0.35))
            elif num == 600:
                # 600: more saturated than base for richness
                saturation = min(100, base_s * 1.25)
            elif num == 700:
                # 700: maintain high saturation for visibility
                saturation = min(100, base_s * 1.2)
            elif num >= 800:
                # 800, 900: maintain high saturation for vibrancy
                saturation = min(100, base_s * 1.15)
            
            # Generate the color
            new_hsl = (base_h, saturation, target_lightness)
            new_rgb = hsl_to_rgb(new_hsl)
            scale[num] = rgb_to_hex(new_rgb)
    
    return scale


def generate_colors_css():
    """Generate colors.css with all color scales."""
    css_lines = [':root {']
    
    for color_name, base_hex in BASE_COLORS.items():
        scale = generate_color_scale(base_hex, color_name)
        css_lines.append(f'\n  /* {color_name.capitalize()} Color Scale */')
        for num in SCALE_NUMBERS:
            css_lines.append(f'  --color-{color_name}-{num}: {scale[num]};')
    
    css_lines.append('}')
    css_lines.append('')
    
    return '\n'.join(css_lines)


def main():
    """Main function to generate colors.css file."""
    # Get the directory where this script is located
    script_dir = os.path.dirname(os.path.abspath(__file__))
    css_dir = os.path.join(script_dir, 'src', 'assets', 'css')
    root_css_path = os.path.join(css_dir, 'root.css')
    
    # Parse base colors from root.css
    global BASE_COLORS
    BASE_COLORS = parse_root_css(root_css_path)
    
    if not BASE_COLORS:
        print(f'Warning: No colors found in {root_css_path}')
        print('Falling back to default colors')
        BASE_COLORS = {
            'blue': '#163D8A',
            'red': '#FF3E41',
            'green': '#5B8A3C',
            'yellow': '#F4AC45',
            'purple': '#CB48B7'
        }
    else:
        print(f'Parsed {len(BASE_COLORS)} colors from root.css: {", ".join(BASE_COLORS.keys())}')
    
    # Generate colors.css
    colors_css = generate_colors_css()
    colors_path = os.path.join(css_dir, 'colors.css')
    with open(colors_path, 'w') as f:
        f.write(colors_css)
    print(f'Generated {colors_path}')


if __name__ == '__main__':
    main()

