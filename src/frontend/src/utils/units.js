import store from "@/assets/js/store";

const METERS_TO_FEET = 3.28084;
const METERS_TO_MILES = 0.000621371;
const METERS_TO_KM = 0.001;
const SQ_METERS_TO_SQ_FEET = 10.7639;
const SQ_METERS_TO_SQ_MILES = 3.861e-7;
const SQ_METERS_TO_SQ_KM = 1e-6;
const SQ_METERS_TO_ACRES = 0.000247105;

/**
 * Get current unit preference from store
 * @returns {'metric' | 'imperial'}
 */
export function getUnitPreference() {
  return store.state.userSettings?.account?.units || 'imperial';
}

/**
 * Convert meters to the user's preferred distance unit value
 * @param {number} meters - Distance in meters
 * @returns {Object} { value: number, unit: string }
 */
export function getDistanceValue(meters) {
  const units = getUnitPreference();
  
  if (units === 'metric') {
    if (meters >= 1000) {
      return { value: meters * METERS_TO_KM, unit: 'km' };
    }
    return { value: meters, unit: 'm' };
  } else {
    // Imperial
    const miles = meters * METERS_TO_MILES;
    if (miles >= 0.1) {
      return { value: miles, unit: 'mi' };
    }
    return { value: meters * METERS_TO_FEET, unit: 'ft' };
  }
}

/**
 * Convert meters to the user's preferred elevation unit value
 * @param {number} meters - Elevation in meters
 * @returns {Object} { value: number, unit: string }
 */
export function getElevationValue(meters) {
  const units = getUnitPreference();
  
  if (units === 'metric') {
    return { value: meters, unit: 'm' };
  } else {
    return { value: meters * METERS_TO_FEET, unit: 'ft' };
  }
}

/**
 * Format distance string based on user preference
 * @param {number} meters - Distance in meters
 * @param {number} decimals - Number of decimal places
 * @returns {string} Formatted string (e.g. "5.2 km")
 */
export function formatDistance(meters, decimals = 2) {
  if (meters === null || meters === undefined) return 'N/A';
  const { value, unit } = getDistanceValue(meters);
  
  // Adjust decimals for smaller units to avoid excessive precision
  const adjustedDecimals = (unit === 'ft' || unit === 'm') ? 0 : decimals;
  
  return `${value.toFixed(adjustedDecimals)} ${unit}`;
}

/**
 * Format elevation string based on user preference
 * @param {number} meters - Elevation in meters
 * @param {number} decimals - Number of decimal places
 * @returns {string} Formatted string (e.g. "1500 ft")
 */
export function formatElevation(meters, decimals = 1) {
  if (meters === null || meters === undefined) return 'N/A';
  const { value, unit } = getElevationValue(meters);
  return `${value.toFixed(decimals)} ${unit}`;
}

/**
 * Format area string based on user preference
 * @param {number} sqMeters - Area in square meters
 * @param {number} decimals - Number of decimal places
 * @returns {string} Formatted string
 */
export function formatArea(sqMeters, decimals = 2) {
  if (sqMeters === null || sqMeters === undefined) return 'N/A';
  const units = getUnitPreference();
  
  if (units === 'metric') {
    if (sqMeters >= 1000000) {
      return `${(sqMeters * SQ_METERS_TO_SQ_KM).toFixed(decimals)} km²`;
    } else if (sqMeters >= 10000) {
      return `${(sqMeters / 10000).toFixed(decimals)} ha`;
    }
    return `${sqMeters.toFixed(0)} m²`;
  } else {
    const sqMiles = sqMeters * SQ_METERS_TO_SQ_MILES;
    if (sqMiles >= 0.1) {
      return `${sqMiles.toFixed(decimals)} mi²`;
    }
    const acres = sqMeters * SQ_METERS_TO_ACRES;
    if (acres >= 0.1) {
      return `${acres.toFixed(decimals)} ac`;
    }
    return `${(sqMeters * SQ_METERS_TO_SQ_FEET).toFixed(0)} ft²`;
  }
}

/**
 * Get raw multiplier for converting meters to user's preferred elevation unit
 * Useful for charts/graphs where we need uniform units
 * @returns {number} Multiplier
 */
export function getElevationMultiplier() {
  return getUnitPreference() === 'imperial' ? METERS_TO_FEET : 1;
}

/**
 * Get raw multiplier for converting meters to user's preferred large distance unit (km/mi)
 * Useful for charts/graphs where we need uniform units
 * @returns {number} Multiplier
 */
export function getDistanceMultiplier() {
  return getUnitPreference() === 'imperial' ? METERS_TO_MILES : METERS_TO_KM;
}

/**
 * Get the label for the elevation unit
 * @returns {string} 'ft' or 'm'
 */
export function getElevationUnitLabel() {
  return getUnitPreference() === 'imperial' ? 'ft' : 'm';
}

/**
 * Get the label for the large distance unit
 * @returns {string} 'mi' or 'km'
 */
export function getDistanceUnitLabel() {
  return getUnitPreference() === 'imperial' ? 'mi' : 'km';
}

