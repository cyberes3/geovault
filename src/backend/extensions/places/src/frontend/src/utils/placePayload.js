/**
 * Build a places API create/update payload with only allowed property fields.
 * @param {object} feature
 * @param {{ name?: string, description?: string|null, address?: string|null }} [overrides]
 */
export function buildPlacePayload(feature, overrides = {}) {
  const properties = feature?.properties ?? {};
  const coords = feature?.geometry?.coordinates ?? [];
  const name = overrides.name !== undefined ? overrides.name : properties.name;
  const description = overrides.description !== undefined ? overrides.description : properties.description;
  const address = overrides.address !== undefined ? overrides.address : properties.address;

  const payloadProperties = {
    name: String(name ?? '').trim(),
    description: description == null || String(description).trim() === ''
      ? null
      : String(description).trim(),
  };
  if (address != null && String(address).trim() !== '') {
    payloadProperties.address = String(address).trim();
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
