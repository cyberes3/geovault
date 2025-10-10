/**
 * Simple test file for geometry simplification
 * Run this in the browser console to test the simplification functions
 */

import { simplifyFeature, simplifyFeatures, getSimplificationStats } from './geometry-simplification.js';

// Test data - a complex LineString with many points
const testLineString = {
  type: 'Feature',
  geometry: {
    type: 'LineString',
    coordinates: [
      [0, 0], [0.1, 0.1], [0.2, 0.15], [0.3, 0.2], [0.4, 0.25], [0.5, 0.3],
      [0.6, 0.35], [0.7, 0.4], [0.8, 0.45], [0.9, 0.5], [1.0, 0.55],
      [1.1, 0.6], [1.2, 0.65], [1.3, 0.7], [1.4, 0.75], [1.5, 0.8],
      [1.6, 0.85], [1.7, 0.9], [1.8, 0.95], [1.9, 1.0], [2.0, 1.05]
    ]
  },
  properties: {
    name: 'Test Line',
    stroke: '#ff0000',
    'stroke-width': 2
  }
};

// Test data - a complex Polygon
const testPolygon = {
  type: 'Feature',
  geometry: {
    type: 'Polygon',
    coordinates: [[
      [0, 0], [0.1, 0.1], [0.2, 0.15], [0.3, 0.2], [0.4, 0.25], [0.5, 0.3],
      [0.6, 0.35], [0.7, 0.4], [0.8, 0.45], [0.9, 0.5], [1.0, 0.55],
      [1.1, 0.6], [1.2, 0.65], [1.3, 0.7], [1.4, 0.75], [1.5, 0.8],
      [1.6, 0.85], [1.7, 0.9], [1.8, 0.95], [1.9, 1.0], [2.0, 1.05],
      [1.9, 1.1], [1.8, 1.15], [1.7, 1.2], [1.6, 1.25], [1.5, 1.3],
      [1.4, 1.35], [1.3, 1.4], [1.2, 1.45], [1.1, 1.5], [1.0, 1.55],
      [0.9, 1.6], [0.8, 1.65], [0.7, 1.7], [0.6, 1.75], [0.5, 1.8],
      [0.4, 1.85], [0.3, 1.9], [0.2, 1.95], [0.1, 2.0], [0, 2.05],
      [-0.1, 2.0], [-0.2, 1.95], [-0.3, 1.9], [-0.4, 1.85], [-0.5, 1.8],
      [-0.6, 1.75], [-0.7, 1.7], [-0.8, 1.65], [-0.9, 1.6], [-1.0, 1.55],
      [-1.1, 1.5], [-1.2, 1.45], [-1.3, 1.4], [-1.4, 1.35], [-1.5, 1.3],
      [-1.6, 1.25], [-1.7, 1.2], [-1.8, 1.15], [-1.9, 1.1], [-2.0, 1.05],
      [-1.9, 1.0], [-1.8, 0.95], [-1.7, 0.9], [-1.6, 0.85], [-1.5, 0.8],
      [-1.4, 0.75], [-1.3, 0.7], [-1.2, 0.65], [-1.1, 0.6], [-1.0, 0.55],
      [-0.9, 0.5], [-0.8, 0.45], [-0.7, 0.4], [-0.6, 0.35], [-0.5, 0.3],
      [-0.4, 0.25], [-0.3, 0.2], [-0.2, 0.15], [-0.1, 0.1], [0, 0]
    ]]
  },
  properties: {
    name: 'Test Polygon',
    fill: '#00ff00',
    stroke: '#000000',
    'stroke-width': 1
  }
};

// Test function
export function runGeometrySimplificationTests() {
  console.log('Running geometry simplification tests...');
  
  // Test LineString simplification at different zoom levels
  console.log('\n=== LineString Simplification Tests ===');
  for (let zoom = 5; zoom <= 15; zoom += 2) {
    const simplified = simplifyFeature(testLineString, zoom);
    const stats = getSimplificationStats(testLineString.geometry, simplified.geometry);
    
    console.log(`Zoom ${zoom}: ${stats.originalCoordinateCount} → ${stats.simplifiedCoordinateCount} coordinates (${stats.reductionPercentage}% reduction)`);
  }
  
  // Test Polygon simplification at different zoom levels
  console.log('\n=== Polygon Simplification Tests ===');
  for (let zoom = 5; zoom <= 15; zoom += 2) {
    const simplified = simplifyFeature(testPolygon, zoom);
    const stats = getSimplificationStats(testPolygon.geometry, simplified.geometry);
    
    console.log(`Zoom ${zoom}: ${stats.originalCoordinateCount} → ${stats.simplifiedCoordinateCount} coordinates (${stats.reductionPercentage}% reduction)`);
  }
  
  // Test performance with multiple features
  console.log('\n=== Performance Test ===');
  const testFeatures = [testLineString, testPolygon];
  const startTime = performance.now();
  const simplifiedFeatures = simplifyFeatures(testFeatures, 10);
  const endTime = performance.now();
  
  console.log(`Simplified ${testFeatures.length} features in ${(endTime - startTime).toFixed(2)}ms`);
  
  console.log('\nTests completed!');
}

// Export test data for manual testing
export { testLineString, testPolygon };
