const COORDS_DISPLAY_LENGTH = 21;

export function formatCoords(coords) {
  if (!coords || coords.length < 2) {
    return '';
  }
  return `${coords[1].toFixed(4)}, ${coords[0].toFixed(4)}`;
}

export function placeLocationLabel(place) {
  const address = place?.properties?.address;
  if (address && String(address).trim()) {
    const trimmed = String(address).trim();
    return trimmed.length <= COORDS_DISPLAY_LENGTH
      ? trimmed
      : `${trimmed.slice(0, COORDS_DISPLAY_LENGTH - 1)}…`;
  }
  return formatCoords(place?.geometry?.coordinates);
}

export function formatCreatedDate(isoString) {
  if (!isoString) {
    return '';
  }
  try {
    const date = new Date(isoString);
    return Number.isNaN(date.getTime())
      ? ''
      : date.toLocaleDateString(undefined, {
          year: 'numeric',
          month: 'short',
          day: 'numeric',
        });
  } catch {
    return '';
  }
}

export function googleMapsUrl(place) {
  const lat = Number(place.geometry.coordinates[1]).toFixed(8);
  const lon = Number(place.geometry.coordinates[0]).toFixed(8);
  const name = (place.properties.name || '').trim();
  let query = `${lat},${lon}`;
  if (name) {
    const safeName = name.replaceAll('(', ' ').replaceAll(')', ' ');
    query += `(${safeName})`;
  }
  return `https://maps.google.com/?q=${encodeURIComponent(query)}`;
}

/**
 * @param {import('../types/places.js').PlaceFeature[]} places
 * @param {string} query
 */
export function filterPlaces(places, query) {
  if (!query) {
    return places;
  }
  const lower = query.toLowerCase();
  return places.filter((place) => {
    const name = place.properties?.name;
    const description = place.properties?.description;
    return (name != null && String(name).toLowerCase().includes(lower))
      || (description != null && String(description).toLowerCase().includes(lower));
  });
}
