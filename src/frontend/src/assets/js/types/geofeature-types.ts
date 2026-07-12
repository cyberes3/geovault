import type { ImportFeatureGeometry, ImportFeatureProperties } from './import-types';

enum GeoFeatureType {
    POINT = 'Point',
    LINESTRING = 'LineString',
    POLYGON = 'Polygon'
}

/**
 * Input shape accepted by `GeoPoint`/`GeoLineString`/`GeoPolygon`: a GeoJSON-ish feature as sent
 * by the import preview endpoints (`geometry.type` is the real geometry type, e.g. `MultiPoint`
 * for a point feature; the wrapper class below normalizes it to one of the three display buckets
 * used for styling).
 */
export interface GeoFeatureProps {
    id?: string | number;
    type?: string;
    geometry: ImportFeatureGeometry;
    properties: ImportFeatureProperties;
}

class GeoFeature {
    id?: string | number;
    type: GeoFeatureType;
    geometry: ImportFeatureGeometry;
    properties: ImportFeatureProperties;

    constructor(props: GeoFeatureProps, type: GeoFeatureType) {
        this.id = props.id;
        this.type = type;
        this.geometry = props.geometry;
        this.properties = props.properties;
    }
}

export class GeoPoint extends GeoFeature {
    constructor(props: GeoFeatureProps) {
        super(props, GeoFeatureType.POINT);
    }
}

export class GeoLineString extends GeoFeature {
    constructor(props: GeoFeatureProps) {
        super(props, GeoFeatureType.LINESTRING);
    }
}

export class GeoPolygon extends GeoFeature {
    constructor(props: GeoFeatureProps) {
        super(props, GeoFeatureType.POLYGON);
    }
}
