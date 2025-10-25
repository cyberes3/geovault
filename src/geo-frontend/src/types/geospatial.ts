/**
 * TypeScript types for geospatial functionality
 */


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


export interface MapConfig {
  center: [number, number];
  zoom: number;
}
