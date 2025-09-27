enum GeoFeatureType {
    POINT = 'Point',
    LINESTRING = 'LineString',
    POLYGON = 'Polygon'
}

interface GeoFeatureProperties {
    name: string;
    description?: string;
    created?: Date;
    software: string;
    software_version: string;
    tags: string[];
}

interface GeoFeatureProps {
    name: string;
    id: string;
    type: GeoFeatureType;
    description?: string;
    geometry: any[];
    properties: GeoFeatureProperties;
}

class GeoFeature {
    id: string;
    type: GeoFeatureType;
    geometry: any[];
    properties: GeoFeatureProperties;

    constructor(props: GeoFeatureProps) {
        this.id = props.id;
        this.type = props.type;
        this.geometry = props.geometry || [];
        this.properties = props.properties;
    }
}

export class GeoPoint extends GeoFeature {
    type: GeoFeatureType = GeoFeatureType.POINT;
    geometry: number[];

    constructor(props: GeoFeatureProps) {
        super(props)
    }
}

export class GeoLineString extends GeoFeature {
    type: GeoFeatureType = GeoFeatureType.LINESTRING;
    geometry: number[][];

    constructor(props: GeoFeatureProps) {
        super(props)
    }
}

export class GeoPolygon extends GeoFeature {
    type: GeoFeatureType = GeoFeatureType.POLYGON;
    geometry: number[][][];

    constructor(props: GeoFeatureProps) {
        super(props)
    }
}
