import { computed, ref } from 'vue';
import { useGeocodingSearch } from '@/composables/useGeocodingSearch.js';

function hasAddressLikeLetters(str) {
  return /[a-zA-Z]/.test(str.replace(/[nsewd]/gi, ''));
}

export function usePlaceForm() {
  const utils = window.gv_core?.GeoVault?.utils ?? null;
  const { geocodeAddress } = useGeocodingSearch();

  const name = ref('');
  const description = ref('');
  const latitude = ref(null);
  const longitude = ref(null);
  const coordinatesInput = ref('');
  const coordinateError = ref('');
  const storedAddress = ref(null);
  const isGeocoding = ref(false);
  const coordinatesValidationTimeout = ref(null);
  const initialFormSnapshot = ref(null);

  const isDirty = computed(() => {
    const snapshot = initialFormSnapshot.value;
    if (snapshot == null) {
      return false;
    }
    return name.value !== snapshot.name
      || description.value !== snapshot.description
      || latitude.value !== snapshot.lat
      || longitude.value !== snapshot.lon
      || (storedAddress.value || '') !== (snapshot.address || '');
  });

  function captureSnapshot() {
    initialFormSnapshot.value = {
      name: name.value,
      description: description.value,
      lat: latitude.value,
      lon: longitude.value,
      address: storedAddress.value || null,
    };
  }

  function setCoords(lat, lon, displayText = null) {
    latitude.value = lat == null ? null : parseFloat(Number(lat).toFixed(6));
    longitude.value = lon == null ? null : parseFloat(Number(lon).toFixed(6));
    if (displayText != null && displayText !== '') {
      coordinatesInput.value = displayText;
      storedAddress.value = displayText;
    } else {
      coordinatesInput.value = latitude.value != null && longitude.value != null
        ? `${latitude.value}, ${longitude.value}`
        : '';
      storedAddress.value = null;
    }
    coordinateError.value = '';
  }

  function validateParsedCoordinatePair(lng, lat) {
    const validateCoordinatesUtil = utils?.validateCoordinates;
    if (!validateCoordinatesUtil) {
      return true;
    }
    const validation = validateCoordinatesUtil([lng, lat], 'Point');
    if (!validation.valid) {
      if (Math.abs(lat) > 90 && Math.abs(lng) <= 90) {
        const swapped = validateCoordinatesUtil([lat, lng], 'Point');
        if (swapped.valid) {
          coordinateError.value = 'Coordinates appear to be swapped. Enter latitude, longitude.';
          return false;
        }
      }
      coordinateError.value = validation.error || 'Invalid coordinates';
      return false;
    }
    return true;
  }

  async function validateCoordinates(options = {}) {
    const { reformatInput = true } = options;
    coordinateError.value = '';
    latitude.value = null;
    longitude.value = null;
    storedAddress.value = null;
    const input = coordinatesInput.value.trim();
    if (!input) {
      return { valid: false, changed: true };
    }

    const parseCoordinates = utils?.parseCoordinates;
    if (!parseCoordinates) {
      return { valid: false, changed: false };
    }

    const coordinates = parseCoordinates(input);
    if (coordinates) {
      if (!validateParsedCoordinatePair(coordinates.lng, coordinates.lat)) {
        latitude.value = null;
        longitude.value = null;
        return { valid: false, changed: true };
      }
      latitude.value = coordinates.lat;
      longitude.value = coordinates.lng;
      if (reformatInput) {
        coordinatesInput.value = `${latitude.value}, ${longitude.value}`;
      }
      storedAddress.value = null;
      return { valid: true, changed: true, panMap: true };
    }

    if (hasAddressLikeLetters(input)) {
      isGeocoding.value = true;
      try {
        const result = await geocodeAddress(input);
        if (!result.ok) {
          coordinateError.value = result.error || 'Address not found';
          return { valid: false, changed: true };
        }
        setCoords(result.lat, result.lon, result.label);
        return { valid: true, changed: true, panMap: true };
      } finally {
        isGeocoding.value = false;
      }
    }

    const looksLikeCoordinates = utils?.looksLikeCoordinates;
    if (looksLikeCoordinates?.(input)) {
      coordinateError.value = 'Invalid coordinate format';
      return { valid: false, changed: true };
    }

    return { valid: false, changed: true };
  }

  function onCoordinatesInput() {
    const input = coordinatesInput.value.trim();
    if (!input) {
      coordinateError.value = '';
    }
    if (coordinatesValidationTimeout.value != null) {
      clearTimeout(coordinatesValidationTimeout.value);
    }
    coordinatesValidationTimeout.value = setTimeout(() => {
      coordinatesValidationTimeout.value = null;
      void validateCoordinates({ reformatInput: false });
    }, 300);
  }

  function resetForm() {
    name.value = '';
    description.value = '';
    latitude.value = null;
    longitude.value = null;
    coordinatesInput.value = '';
    coordinateError.value = '';
    storedAddress.value = null;
    initialFormSnapshot.value = {
      name: '',
      description: '',
      lat: null,
      lon: null,
      address: null,
    };
    if (coordinatesValidationTimeout.value != null) {
      clearTimeout(coordinatesValidationTimeout.value);
      coordinatesValidationTimeout.value = null;
    }
  }

  function loadFromFeature(feature) {
    name.value = feature.properties?.name ? String(feature.properties.name) : '';
    description.value = feature.properties?.description ? String(feature.properties.description) : '';
    const coords = feature.geometry?.coordinates;
    if (coords && coords.length >= 2) {
      const addressProp = feature.properties?.address;
      if (addressProp) {
        setCoords(coords[1], coords[0], String(addressProp));
      } else {
        setCoords(coords[1], coords[0]);
      }
    }
    captureSnapshot();
  }

  function buildPayload() {
    let lat = latitude.value;
    let lng = longitude.value;
    if (lat == null || lng == null) {
      const input = coordinatesInput.value.trim();
      const parseCoordinates = utils?.parseCoordinates;
      if (parseCoordinates) {
        const coordinates = parseCoordinates(input);
        if (coordinates) {
          lat = coordinates.lat;
          lng = coordinates.lng;
        }
      }
    }
    if (lat == null || lng == null || !validateParsedCoordinatePair(lng, lat)) {
      return null;
    }

    const properties = {
      name: name.value.trim(),
      description: (description.value || '').trim() || null,
    };
    if (storedAddress.value) {
      properties.address = storedAddress.value;
    }
    return {
      type: 'Feature',
      geometry: {
        type: 'Point',
        coordinates: [lng, lat],
      },
      properties,
    };
  }

  function getMarkerCoordinates() {
    const lat = latitude.value;
    const lon = longitude.value;
    if (lat == null || lon == null || !Number.isFinite(lat) || !Number.isFinite(lon)) {
      return null;
    }
    return { lat, lon };
  }

  return {
    name,
    description,
    latitude,
    longitude,
    coordinatesInput,
    coordinateError,
    storedAddress,
    isGeocoding,
    isDirty,
    setCoords,
    validateCoordinates,
    onCoordinatesInput,
    resetForm,
    loadFromFeature,
    buildPayload,
    captureSnapshot,
    getMarkerCoordinates,
    validateParsedCoordinatePair,
  };
}
