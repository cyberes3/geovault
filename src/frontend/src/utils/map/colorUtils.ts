/**
 * Color utilities for map styling.
 */

/**
 * Convert hex color string to RGB array [r, g, b]
 */
export function hexToRgb(hex: string): [number, number, number] | null {
  const shorthandRegex = /^#?([a-f\d])([a-f\d])([a-f\d])$/i
  hex = hex.replace(shorthandRegex, (_m, r, g, b) => r + r + g + g + b + b)
  const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex)
  return result
    ? [parseInt(result[1], 16), parseInt(result[2], 16), parseInt(result[3], 16)]
    : null
}

/**
 * Get the inverse/opposite color of a hex color
 */
export function getInverseColor(hex: string): string {
  const rgb = hexToRgb(hex)
  if (!rgb) {
    return '#000000' // Default to black if conversion fails
  }
  // Invert each RGB component
  const inverted = rgb.map(c => 255 - c)
  // Convert back to hex
  return (
    '#' +
    inverted
      .map(c => {
        const h = c.toString(16)
        return h.length === 1 ? '0' + h : h
      })
      .join('')
  )
}


