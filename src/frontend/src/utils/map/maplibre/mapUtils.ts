/** Generate a bounding box key for caching. `bounds` is [minLon, minLat, maxLon, maxLat]. */
export function getBoundingBoxKey(bounds: [number, number, number, number], zoom: number): string {
  const roundedZoom = Math.floor(zoom)
  return `${bounds[0].toFixed(4)},${bounds[1].toFixed(4)},${bounds[2].toFixed(4)},${bounds[3].toFixed(4)}_${roundedZoom}`
}

/** Convert bounds to string format for API requests. `bounds` is [minLon, minLat, maxLon, maxLat]. */
export function getBoundingBoxString(bounds: [number, number, number, number]): string {
  return `${bounds[0]},${bounds[1]},${bounds[2]},${bounds[3]}`
}

