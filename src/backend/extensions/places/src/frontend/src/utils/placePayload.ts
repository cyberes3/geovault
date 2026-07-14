import type { PlaceFeature, PlacePayload, PlacePayloadOverrides } from '../types/places';

/** Build a places API create/update payload with only allowed property fields. */
export function buildPlacePayload(feature: PlaceFeature, overrides: PlacePayloadOverrides = {}): PlacePayload {
  const properties = feature.properties;
  const coords = feature.geometry.coordinates;
  const name = overrides.name ?? properties.name;
  const description = overrides.description ?? properties.description;
  const address = overrides.address ?? properties.address;

  const payloadProperties: PlacePayload['properties'] = {
    name: (name ?? '').trim(),
    description: description == null || description.trim() === ''
      ? null
      : description.trim(),
  };
  if (address != null && address.trim() !== '') {
    payloadProperties.address = address.trim();
  }

  return {
    type: 'Feature',
    geometry: {
      type: 'Point',
      coordinates: [coords[0], coords[1]],
    },
    properties: payloadProperties,
  };
}
