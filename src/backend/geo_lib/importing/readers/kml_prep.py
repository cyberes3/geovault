import re


def remove_kml_namespace_prefixes(content: str) -> str:
    stripped = re.sub(r'(<\/?)(\w+):', r'\1', content)
    return re.sub(r'\s+xmlns:\w+="[^"]*"', '', stripped)


def apply_kml_times(feature: dict) -> None:
    properties = feature.get('properties')
    if not isinstance(properties, dict):
        return
    if properties.get('created'):
        return
    timespan = properties.get('timespan')
    if isinstance(timespan, dict) and timespan.get('begin'):
        properties['created'] = timespan['begin']
        return
    timestamp = properties.get('timestamp')
    if timestamp:
        properties['created'] = timestamp


def apply_kml_times_collection(geojson_data: dict) -> dict:
    for feature in geojson_data.get('features') or []:
        if isinstance(feature, dict):
            apply_kml_times(feature)
    return geojson_data
