/**
 * TypeScript types for geospatial functionality
 */

export interface SimplificationOptions {
  baseTolerance: number;
  minZoom: number;
  maxZoom: number;
  enableSimplification: boolean;
}

export interface SimplificationSettings {
  minImmediateZoomChangeThreshold: number;
}

export interface FeatureCountThresholds {
  tier1: number; // 0-20% of max features - minimal simplification
  tier2: number; // 20-40% of max features - light simplification  
  tier3: number; // 40-60% of max features - moderate simplification
  tier4: number; // 60-80% of max features - aggressive simplification
  tier5: number; // 80-100% of max features - maximum simplification
}

export interface SimplificationStats {
  originalCoordinateCount: number;
  simplifiedCoordinateCount: number;
  reductionPercentage: string;
  compressionRatio: string;
  processingTime?: number;
  featureCount?: number;
  zoom?: number;
  tier?: number;
  multiplier?: number;
}

export interface PerformanceThresholds {
  maxProcessingTime: number;
  maxFeaturesForFullDetail: number;
  minZoomForAggressiveSimplification: number;
}

export interface GeoJsonFeature {
  type: 'Feature';
  geometry: {
    type: 'Point' | 'LineString' | 'Polygon' | 'MultiPoint' | 'MultiLineString' | 'MultiPolygon';
    coordinates: any;
  };
  properties: Record<string, any>;
  geojson_hash?: string;
}

export interface GeoJsonFeatureCollection {
  type: 'FeatureCollection';
  features: GeoJsonFeature[];
}

export interface SimplificationTier {
  level: 1 | 2 | 3 | 4 | 5;
  multiplier: number;
  description: string;
  featureCountRange: string;
}

export interface MapConfig {
  center: [number, number];
  zoom: number;
}
