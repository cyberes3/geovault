import type { PlaceFeature } from '../types/places';

const COORDS_DISPLAY_LENGTH = 21;

export function formatCoords(coords: number[] | undefined | null): string {
  if (!coords || coords.length < 2) {
    return '';
  }
  return `${coords[1].toFixed(4)}, ${coords[0].toFixed(4)}`;
}

export function placeLocationLabel(place: PlaceFeature | undefined | null): string {
  const address = place?.properties.address;
  if (address?.trim()) {
    const trimmed = address.trim();
    return trimmed.length <= COORDS_DISPLAY_LENGTH
      ? trimmed
      : `${trimmed.slice(0, COORDS_DISPLAY_LENGTH - 1)}…`;
  }
  return formatCoords(place?.geometry.coordinates);
}

export function formatCreatedDate(isoString: string | undefined | null): string {
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

export function googleMapsUrl(place: PlaceFeature): string {
  const lat = Number(place.geometry.coordinates[1]).toFixed(8);
  const lon = Number(place.geometry.coordinates[0]).toFixed(8);
  const name = (place.properties.name ?? '').trim();
  let query = `${lat},${lon}`;
  if (name) {
    const safeName = name.replace(/[()]/g, ' ');
    query += `(${safeName})`;
  }
  return `https://maps.google.com/?q=${encodeURIComponent(query)}`;
}

export function filterPlaces(places: PlaceFeature[], query: string): PlaceFeature[] {
  if (!query) {
    return places;
  }
  const lower = query.toLowerCase();
  return places.filter((place) => {
    const name = place.properties.name;
    const description = place.properties.description;
    return (name?.toLowerCase().includes(lower) ?? false)
      || (description?.toLowerCase().includes(lower) ?? false);
  });
}
