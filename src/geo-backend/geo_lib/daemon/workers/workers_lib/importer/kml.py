import io
import json
import zipfile
from typing import Union, Tuple

import geojson
import kml2geojson
from dateparser import parse
from geojson import Point, LineString, Polygon, FeatureCollection

from geo_lib.daemon.workers.workers_lib.importer.logging import ImportLog
from geo_lib.types.geojson import GeojsonRawProperty


def kmz_to_kml(kml_bytes: Union[str, bytes]) -> str:
    if isinstance(kml_bytes, str):
        kml_bytes = kml_bytes.encode('utf-8')
    try:
        # Try to open as a zipfile (KMZ)
        with zipfile.ZipFile(io.BytesIO(kml_bytes), 'r') as kmz:
            # Find the first .kml file in the zipfile
            kml_file = [name for name in kmz.namelist() if name.endswith('.kml')][0]
            return kmz.read(kml_file).decode('utf-8')
    except zipfile.BadZipFile:
        # If not a zipfile, assume it's a KML file
        return kml_bytes.decode('utf-8')


def kml_to_geojson(kml_bytes) -> Tuple[dict, ImportLog]:
    # TODO: preserve KML object styling, such as color and opacity
    doc = kmz_to_kml(kml_bytes)
    converted_kml = kml2geojson.main.convert(io.BytesIO(doc.encode('utf-8')))
    features, import_log = process_feature(converted_kml)
    data = {
        'type': 'FeatureCollection',
        'features': features
    }
    return load_geojson_type(data), import_log


def process_feature(converted_kml) -> Tuple[list, ImportLog]:
    features = []
    import_log = ImportLog()
    for feature in converted_kml[0]['features']:
        if feature['geometry']['type'] in ['Point', 'LineString', 'Polygon']:
            if feature['properties'].get('times'):
                for i, timestamp_str in enumerate(feature['properties']['times']):
                    timestamp = int(parse(timestamp_str).timestamp() * 1000)
                    feature['geometry']['coordinates'][i].append(timestamp)
            feature['properties'] = GeojsonRawProperty(**feature['properties']).model_dump()
            features.append(feature)
        elif feature['geometry']['type'] == 'GeometryCollection':
            # Handle GeometryCollection by extracting supported geometry types
            # For polygons, prioritize Polygon geometry over Point (which are often just labels)
            supported_geometries = []
            polygon_geometries = []

            for geom in feature['geometry']['geometries']:
                if geom['type'] == 'Polygon':
                    polygon_geometries.append(geom)
                elif geom['type'] in ['Point', 'LineString']:
                    supported_geometries.append(geom)

            # If we have polygons, use those instead of other geometries
            if polygon_geometries:
                for geom in polygon_geometries:
                    new_feature = {
                        'type': 'Feature',
                        'geometry': geom,
                        'properties': feature['properties'].copy()
                    }
                    if new_feature['properties'].get('times'):
                        for i, timestamp_str in enumerate(new_feature['properties']['times']):
                            timestamp = int(parse(timestamp_str).timestamp() * 1000)
                            new_feature['geometry']['coordinates'][i].append(timestamp)
                    new_feature['properties'] = GeojsonRawProperty(**new_feature['properties']).model_dump()
                    features.append(new_feature)
            elif supported_geometries:
                # Create separate features for each supported geometry (no polygons found)
                for geom in supported_geometries:
                    new_feature = {
                        'type': 'Feature',
                        'geometry': geom,
                        'properties': feature['properties'].copy()
                    }
                    if new_feature['properties'].get('times'):
                        for i, timestamp_str in enumerate(new_feature['properties']['times']):
                            timestamp = int(parse(timestamp_str).timestamp() * 1000)
                            new_feature['geometry']['coordinates'][i].append(timestamp)
                    new_feature['properties'] = GeojsonRawProperty(**new_feature['properties']).model_dump()
                    features.append(new_feature)
            else:
                import_log.add(f'GeometryCollection contains no supported geometry types, skipping')
        else:
            # Log the error
            if feature['properties'].get('type'):
                import_log.add(f'Feature type {feature["properties"]["type"]} not supported, skipping')
            else:
                import_log.add(f'Encountered unknown feature type, skipping')
    return features, import_log


def load_geojson_type(data: dict) -> dict:
    features = []
    for feature in data['features']:
        if feature['geometry']['type'] == 'Point':
            features.append(
                Point(coordinates=feature['geometry']['coordinates'], properties=feature['properties'])
            )
        elif feature['geometry']['type'] == 'LineString':
            features.append(
                LineString(coordinates=feature['geometry']['coordinates'], properties=feature['properties'])
            )
        elif feature['geometry']['type'] == 'Polygon':
            features.append(
                Polygon(coordinates=feature['geometry']['coordinates'], properties=feature['properties'])
            )
    collection = FeatureCollection(features)
    geojson_dict = json.loads(geojson.dumps(collection, sort_keys=True))
    for item in geojson_dict['features']:
        item['geometry'] = {
            'type': item.pop('type'),
            'coordinates': item.pop('coordinates'),
        }
        item['type'] = 'Feature'
    return geojson_dict
