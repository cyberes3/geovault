"""Canonical feature property keys and ingest helpers."""

ICON_READ_ALIASES = (
    'icon',
    'icon-href',
    'iconUrl',
    'icon_url',
    'marker-icon',
    'marker-symbol',
    'symbol',
)

DEFAULT_FEATURE_NAME = 'Unnamed Feature'


def ingest_raw_properties(properties: dict) -> dict:
    """Normalize raw togeojson keys before the property whitelist."""
    if not isinstance(properties, dict):
        return properties

    feature_tags = properties.pop('feature_tags', None)
    if feature_tags is not None and not properties.get('tags'):
        properties['tags'] = feature_tags

    name = properties.get('name')
    if name is None or (isinstance(name, str) and name.strip() == ''):
        properties['name'] = DEFAULT_FEATURE_NAME

    return properties


def ingest_icon_properties(properties: dict) -> dict:
    """Read icon aliases and write only the canonical `icon` key."""
    if not isinstance(properties, dict):
        return properties

    icon_value = None
    for key in ICON_READ_ALIASES:
        raw = properties.get(key)
        if isinstance(raw, str) and raw.strip():
            icon_value = raw
            break

    for key in ICON_READ_ALIASES:
        properties.pop(key, None)

    if icon_value is not None:
        properties['icon'] = icon_value

    return properties
