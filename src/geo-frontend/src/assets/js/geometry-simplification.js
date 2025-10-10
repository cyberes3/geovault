/**
 * Geometry simplification utilities for improving map performance
 * Implements Douglas-Peucker algorithm and zoom-based simplification
 */

/**
 * Calculate the perpendicular distance from a point to a line segment
 * @param {Array} point - [x, y] coordinate
 * @param {Array} lineStart - [x, y] start of line segment
 * @param {Array} lineEnd - [x, y] end of line segment
 * @returns {number} Perpendicular distance
 */
function perpendicularDistance(point, lineStart, lineEnd) {
  const [x0, y0] = point;
  const [x1, y1] = lineStart;
  const [x2, y2] = lineEnd;
  
  const A = x0 - x1;
  const B = y0 - y1;
  const C = x2 - x1;
  const D = y2 - y1;
  
  const dot = A * C + B * D;
  const lenSq = C * C + D * D;
  
  if (lenSq === 0) {
    // Line segment is actually a point
    return Math.sqrt(A * A + B * B);
  }
  
  const param = dot / lenSq;
  
  let xx, yy;
  if (param < 0) {
    xx = x1;
    yy = y1;
  } else if (param > 1) {
    xx = x2;
    yy = y2;
  } else {
    xx = x1 + param * C;
    yy = y1 + param * D;
  }
  
  const dx = x0 - xx;
  const dy = y0 - yy;
  return Math.sqrt(dx * dx + dy * dy);
}

/**
 * Optimized Douglas-Peucker line simplification algorithm
 * @param {Array} coordinates - Array of [x, y] coordinates
 * @param {number} tolerance - Simplification tolerance (higher = more simplified)
 * @returns {Array} Simplified coordinates
 */
function douglasPeucker(coordinates, tolerance) {
  if (coordinates.length <= 2) {
    return coordinates;
  }
  
  // Early exit for very small tolerances (no simplification needed)
  if (tolerance < 1e-10) {
    return coordinates;
  }
  
  // Use iterative approach instead of recursive for better performance
  const result = [];
  const stack = [{ start: 0, end: coordinates.length - 1 }];
  
  while (stack.length > 0) {
    const { start, end } = stack.pop();
    
    if (end - start <= 1) {
      // Add the point(s)
      if (start === end) {
        result.push(coordinates[start]);
      } else {
        result.push(coordinates[start]);
        result.push(coordinates[end]);
      }
      continue;
    }
    
    // Find the point with the maximum distance
    let maxDistance = 0;
    let maxIndex = start;
    
    const startPoint = coordinates[start];
    const endPoint = coordinates[end];
    
    // Pre-calculate line vector for efficiency
    const dx = endPoint[0] - startPoint[0];
    const dy = endPoint[1] - startPoint[1];
    const lineLengthSq = dx * dx + dy * dy;
    
    for (let i = start + 1; i < end; i++) {
      const distance = perpendicularDistanceOptimized(
        coordinates[i],
        startPoint,
        endPoint,
        dx,
        dy,
        lineLengthSq
      );
      
      if (distance > maxDistance) {
        maxDistance = distance;
        maxIndex = i;
      }
    }
    
    // If max distance is greater than tolerance, split and continue
    if (maxDistance > tolerance) {
      stack.push({ start: maxIndex, end: end });
      stack.push({ start: start, end: maxIndex });
    } else {
      // All points are within tolerance, add endpoints
      result.push(startPoint);
      result.push(endPoint);
    }
  }
  
  // Remove duplicate consecutive points
  const finalResult = [];
  for (let i = 0; i < result.length; i++) {
    if (i === 0 || result[i][0] !== result[i-1][0] || result[i][1] !== result[i-1][1]) {
      finalResult.push(result[i]);
    }
  }
  
  return finalResult;
}

/**
 * Optimized perpendicular distance calculation
 * @param {Array} point - [x, y] coordinate
 * @param {Array} lineStart - [x, y] start of line segment
 * @param {Array} lineEnd - [x, y] end of line segment
 * @param {number} dx - Pre-calculated line vector x component
 * @param {number} dy - Pre-calculated line vector y component
 * @param {number} lineLengthSq - Pre-calculated line length squared
 * @returns {number} Perpendicular distance
 */
function perpendicularDistanceOptimized(point, lineStart, lineEnd, dx, dy, lineLengthSq) {
  const [x0, y0] = point;
  const [x1, y1] = lineStart;
  
  if (lineLengthSq === 0) {
    // Line segment is actually a point
    const dx2 = x0 - x1;
    const dy2 = y0 - y1;
    return Math.sqrt(dx2 * dx2 + dy2 * dy2);
  }
  
  const A = x0 - x1;
  const B = y0 - y1;
  
  const dot = A * dx + B * dy;
  const param = dot / lineLengthSq;
  
  let xx, yy;
  if (param < 0) {
    xx = x1;
    yy = y1;
  } else if (param > 1) {
    xx = lineEnd[0];
    yy = lineEnd[1];
  } else {
    xx = x1 + param * dx;
    yy = y1 + param * dy;
  }
  
  const dx2 = x0 - xx;
  const dy2 = y0 - yy;
  return Math.sqrt(dx2 * dx2 + dy2 * dy2);
}

/**
 * Calculate simplification tolerance based on zoom level
 * @param {number} zoom - Current zoom level
 * @param {number} baseTolerance - Base tolerance for zoom level 10
 * @returns {number} Calculated tolerance
 */
function calculateToleranceFromZoom(zoom, baseTolerance = 0.001) {
  // Higher zoom = lower tolerance (more detail)
  // Lower zoom = higher tolerance (less detail)
  // Use exponential scaling for smooth transitions
  const zoomFactor = Math.pow(2, 10 - zoom);
  return baseTolerance * zoomFactor;
}

/**
 * Calculate adaptive tolerance based on geometry complexity
 * @param {Object} geometry - GeoJSON geometry
 * @param {number} baseTolerance - Base tolerance
 * @returns {number} Adaptive tolerance
 */
function calculateAdaptiveTolerance(geometry, baseTolerance) {
  const coordCount = countCoordinates(geometry);
  
  // For very complex geometries (>1000 coordinates), increase tolerance
  if (coordCount > 1000) {
    return baseTolerance * Math.min(coordCount / 1000, 5); // Max 5x increase
  }
  
  // For simple geometries (<100 coordinates), decrease tolerance for better quality
  if (coordCount < 100) {
    return baseTolerance * 0.5;
  }
  
  return baseTolerance;
}

/**
 * Simplify a LineString geometry
 * @param {Array} coordinates - LineString coordinates
 * @param {number} tolerance - Simplification tolerance
 * @returns {Array} Simplified coordinates
 */
function simplifyLineString(coordinates, tolerance) {
  if (!coordinates || coordinates.length < 2) {
    return coordinates;
  }
  
  return douglasPeucker(coordinates, tolerance);
}

/**
 * Simplify a Polygon geometry
 * @param {Array} coordinates - Polygon coordinates (array of rings)
 * @param {number} tolerance - Simplification tolerance
 * @returns {Array} Simplified coordinates
 */
function simplifyPolygon(coordinates, tolerance) {
  if (!coordinates || !Array.isArray(coordinates)) {
    return coordinates;
  }
  
  return coordinates.map(ring => {
    if (!Array.isArray(ring) || ring.length < 3) {
      return ring;
    }
    
    // For polygons, we need to preserve the first and last points (which should be the same)
    const simplified = douglasPeucker(ring, tolerance);
    
    // Ensure the polygon is closed (first and last points are the same)
    if (simplified.length > 2) {
      const first = simplified[0];
      const last = simplified[simplified.length - 1];
      
      if (first[0] !== last[0] || first[1] !== last[1]) {
        simplified.push([first[0], first[1]]);
      }
    }
    
    return simplified;
  });
}

/**
 * Simplify a MultiLineString geometry
 * @param {Array} coordinates - MultiLineString coordinates
 * @param {number} tolerance - Simplification tolerance
 * @returns {Array} Simplified coordinates
 */
function simplifyMultiLineString(coordinates, tolerance) {
  if (!coordinates || !Array.isArray(coordinates)) {
    return coordinates;
  }
  
  return coordinates.map(lineString => 
    simplifyLineString(lineString, tolerance)
  );
}

/**
 * Simplify a MultiPolygon geometry
 * @param {Array} coordinates - MultiPolygon coordinates
 * @param {number} tolerance - Simplification tolerance
 * @returns {Array} Simplified coordinates
 */
function simplifyMultiPolygon(coordinates, tolerance) {
  if (!coordinates || !Array.isArray(coordinates)) {
    return coordinates;
  }
  
  return coordinates.map(polygon => 
    simplifyPolygon(polygon, tolerance)
  );
}

/**
 * Simplify a geometry based on its type
 * @param {Object} geometry - GeoJSON geometry object
 * @param {number} tolerance - Simplification tolerance
 * @returns {Object} Simplified geometry
 */
function simplifyGeometry(geometry, tolerance) {
  if (!geometry || !geometry.coordinates) {
    return geometry;
  }
  
  const { type, coordinates } = geometry;
  
  let simplifiedCoordinates;
  
  switch (type) {
    case 'LineString':
      simplifiedCoordinates = simplifyLineString(coordinates, tolerance);
      break;
    case 'Polygon':
      simplifiedCoordinates = simplifyPolygon(coordinates, tolerance);
      break;
    case 'MultiLineString':
      simplifiedCoordinates = simplifyMultiLineString(coordinates, tolerance);
      break;
    case 'MultiPolygon':
      simplifiedCoordinates = simplifyMultiPolygon(coordinates, tolerance);
      break;
    case 'Point':
    case 'MultiPoint':
      // Points don't need simplification
      return geometry;
    default:
      // Unknown geometry type, return as-is
      return geometry;
  }
  
  return {
    ...geometry,
    coordinates: simplifiedCoordinates
  };
}

/**
 * Simplify a GeoJSON feature
 * @param {Object} feature - GeoJSON feature
 * @param {number} zoom - Current zoom level
 * @param {Object} options - Simplification options
 * @param {number} featureCountMultiplier - Multiplier based on feature count (optional)
 * @returns {Object} Simplified feature
 */
export function simplifyFeature(feature, zoom, options = {}, featureCountMultiplier = 1.0) {
  if (!feature || !feature.geometry) {
    return feature;
  }
  
  const {
    baseTolerance = 0.001,
    minZoom = 1,
    maxZoom = 20,
    enableSimplification = true
  } = options;
  
  // Don't simplify if disabled or zoom is out of range
  if (!enableSimplification || zoom < minZoom || zoom > maxZoom) {
    return feature;
  }
  
  // Calculate tolerance based on zoom level
  let tolerance = calculateToleranceFromZoom(zoom, baseTolerance);
  
  // Apply feature count multiplier for dynamic scaling
  tolerance = tolerance * featureCountMultiplier;
  
  // Apply adaptive tolerance based on geometry complexity
  tolerance = calculateAdaptiveTolerance(feature.geometry, tolerance);
  
  // Simplify the geometry
  const simplifiedGeometry = simplifyGeometry(feature.geometry, tolerance);
  
  return {
    ...feature,
    geometry: simplifiedGeometry
  };
}

/**
 * Simplify an array of GeoJSON features
 * @param {Array} features - Array of GeoJSON features
 * @param {number} zoom - Current zoom level
 * @param {Object} options - Simplification options
 * @param {number} featureCountMultiplier - Multiplier based on feature count (optional)
 * @returns {Array} Array of simplified features
 */
export function simplifyFeatures(features, zoom, options = {}, featureCountMultiplier = 1.0) {
  if (!Array.isArray(features)) {
    return features;
  }
  
  return features.map(feature => simplifyFeature(feature, zoom, options, featureCountMultiplier));
}

/**
 * Simplify a GeoJSON FeatureCollection
 * @param {Object} featureCollection - GeoJSON FeatureCollection
 * @param {number} zoom - Current zoom level
 * @param {Object} options - Simplification options
 * @param {number} featureCountMultiplier - Multiplier based on feature count (optional)
 * @returns {Object} Simplified FeatureCollection
 */
export function simplifyFeatureCollection(featureCollection, zoom, options = {}, featureCountMultiplier = 1.0) {
  if (!featureCollection || featureCollection.type !== 'FeatureCollection') {
    return featureCollection;
  }
  
  const simplifiedFeatures = simplifyFeatures(featureCollection.features, zoom, options, featureCountMultiplier);
  
  return {
    ...featureCollection,
    features: simplifiedFeatures
  };
}

/**
 * Get simplification statistics for debugging
 * @param {Object} originalGeometry - Original geometry
 * @param {Object} simplifiedGeometry - Simplified geometry
 * @returns {Object} Statistics object
 */
export function getSimplificationStats(originalGeometry, simplifiedGeometry) {
  if (!originalGeometry || !simplifiedGeometry) {
    return null;
  }
  
  const originalCoords = countCoordinates(originalGeometry);
  const simplifiedCoords = countCoordinates(simplifiedGeometry);
  
  return {
    originalCoordinateCount: originalCoords,
    simplifiedCoordinateCount: simplifiedCoords,
    reductionPercentage: originalCoords > 0 ? 
      ((originalCoords - simplifiedCoords) / originalCoords * 100).toFixed(2) : 0,
    compressionRatio: originalCoords > 0 ? 
      (originalCoords / simplifiedCoords).toFixed(2) : 1
  };
}

/**
 * Count total coordinates in a geometry
 * @param {Object} geometry - GeoJSON geometry
 * @returns {number} Total coordinate count
 */
export function countCoordinates(geometry) {
  if (!geometry || !geometry.coordinates) {
    return 0;
  }
  
  const { type, coordinates } = geometry;
  
  switch (type) {
    case 'Point':
      return 1;
    case 'LineString':
      return coordinates.length;
    case 'Polygon':
      return coordinates.reduce((sum, ring) => sum + ring.length, 0);
    case 'MultiPoint':
      return coordinates.length;
    case 'MultiLineString':
      return coordinates.reduce((sum, line) => sum + line.length, 0);
    case 'MultiPolygon':
      return coordinates.reduce((sum, polygon) => 
        sum + polygon.reduce((ringSum, ring) => ringSum + ring.length, 0), 0);
    default:
      return 0;
  }
}
