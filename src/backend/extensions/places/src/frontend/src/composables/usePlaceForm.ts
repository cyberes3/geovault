import { computed, ref, type ComputedRef, type Ref } from 'vue';
import { useGeocodingSearch } from '@/composables/useGeocodingSearch';
import type { PlaceFeature, PlacePayload } from '@/types/places';

function hasAddressLikeLetters(str: string): boolean {
  return /[a-zA-Z]/.test(str.replace(/[nsewd]/gi, ''));
}

interface FormSnapshot {
  name: string;
  description: string;
  lat: number | null;
  lon: number | null;
  address: string | null;
}

export interface ValidateCoordinatesOptions {
  reformatInput?: boolean;
}

export interface ValidateCoordinatesResult {
  valid: boolean;
  changed: boolean;
  panMap?: boolean;
}

export interface MarkerCoordinates {
  lat: number;
  lon: number;
}

export interface UsePlaceFormReturn {
  name: Ref<string>;
  description: Ref<string>;
  latitude: Ref<number | null>;
  longitude: Ref<number | null>;
  coordinatesInput: Ref<string>;
  coordinateError: Ref<string>;
  storedAddress: Ref<string | null>;
  isGeocoding: Ref<boolean>;
  isDirty: ComputedRef<boolean>;
  setCoords: (lat: number | null, lon: number | null, displayText?: string | null) => void;
  validateCoordinates: (options?: ValidateCoordinatesOptions) => Promise<ValidateCoordinatesResult>;
  onCoordinatesInput: () => void;
  resetForm: () => void;
  loadFromFeature: (feature: PlaceFeature) => void;
  buildPayload: () => PlacePayload | null;
  captureSnapshot: () => void;
  getMarkerCoordinates: () => MarkerCoordinates | null;
  validateParsedCoordinatePair: (lng: number, lat: number) => boolean;
}

export function usePlaceForm(): UsePlaceFormReturn {
  const utils = window.gv_core.GeoVault.utils;
  const { geocodeAddress } = useGeocodingSearch();

  const name = ref('');
  const description = ref('');
  const latitude: Ref<number | null> = ref(null);
  const longitude: Ref<number | null> = ref(null);
  const coordinatesInput = ref('');
  const coordinateError = ref('');
  const storedAddress: Ref<string | null> = ref(null);
  const isGeocoding = ref(false);
  const coordinatesValidationTimeout: Ref<ReturnType<typeof setTimeout> | null> = ref(null);
  const initialFormSnapshot: Ref<FormSnapshot | null> = ref(null);

  const isDirty = computed((): boolean => {
    const snapshot = initialFormSnapshot.value;
    if (snapshot == null) {
      return false;
    }
    return name.value !== snapshot.name
      || description.value !== snapshot.description
      || latitude.value !== snapshot.lat
      || longitude.value !== snapshot.lon
      || (storedAddress.value ?? '') !== (snapshot.address ?? '');
  });

  function captureSnapshot(): void {
    initialFormSnapshot.value = {
      name: name.value,
      description: description.value,
      lat: latitude.value,
      lon: longitude.value,
      address: storedAddress.value ?? null,
    };
  }

  function setCoords(lat: number | null, lon: number | null, displayText: string | null = null): void {
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

  function validateParsedCoordinatePair(lng: number, lat: number): boolean {
    const validation = utils.validateCoordinates([lng, lat], 'Point');
    if (!validation.valid) {
      if (Math.abs(lat) > 90 && Math.abs(lng) <= 90) {
        const swapped = utils.validateCoordinates([lat, lng], 'Point');
        if (swapped.valid) {
          coordinateError.value = 'Coordinates appear to be swapped. Enter latitude, longitude.';
          return false;
        }
      }
      coordinateError.value = validation.error ?? 'Invalid coordinates';
      return false;
    }
    return true;
  }

  async function validateCoordinates({ reformatInput = true }: ValidateCoordinatesOptions = {}): Promise<ValidateCoordinatesResult> {
    coordinateError.value = '';
    latitude.value = null;
    longitude.value = null;
    storedAddress.value = null;
    const input = coordinatesInput.value.trim();
    if (!input) {
      return { valid: false, changed: true };
    }

    const coordinates = utils.parseCoordinates(input);
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
          coordinateError.value = result.error ?? 'Address not found';
          return { valid: false, changed: true };
        }
        setCoords(result.lat ?? null, result.lon ?? null, result.label);
        return { valid: true, changed: true, panMap: true };
      } finally {
        isGeocoding.value = false;
      }
    }

    if (utils.looksLikeCoordinates(input)) {
      coordinateError.value = 'Invalid coordinate format';
      return { valid: false, changed: true };
    }

    return { valid: false, changed: true };
  }

  function onCoordinatesInput(): void {
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

  function resetForm(): void {
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

  function loadFromFeature(feature: PlaceFeature): void {
    name.value = feature.properties.name ? String(feature.properties.name) : '';
    description.value = feature.properties.description ? String(feature.properties.description) : '';
    const coords = feature.geometry.coordinates;
    if (coords.length >= 2) {
      const addressProp = feature.properties.address;
      if (addressProp) {
        setCoords(coords[1], coords[0], String(addressProp));
      } else {
        setCoords(coords[1], coords[0]);
      }
    }
    captureSnapshot();
  }

  function buildPayload(): PlacePayload | null {
    let lat = latitude.value;
    let lng = longitude.value;
    if (lat == null || lng == null) {
      const input = coordinatesInput.value.trim();
      const coordinates = utils.parseCoordinates(input);
      if (coordinates) {
        lat = coordinates.lat;
        lng = coordinates.lng;
      }
    }
    if (lat == null || lng == null || !validateParsedCoordinatePair(lng, lat)) {
      return null;
    }

    const properties: PlacePayload['properties'] = {
      name: name.value.trim(),
      description: description.value.trim() || null,
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

  function getMarkerCoordinates(): MarkerCoordinates | null {
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
