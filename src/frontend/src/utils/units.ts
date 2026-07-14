import store from "@/assets/js/store";

interface UserSettingsGetterShape {
  account?: {
    units?: 'metric' | 'imperial';
  };
}

interface RootGetters {
  'userSettings/userSettings': UserSettingsGetterShape | null;
}

const METERS_TO_FEET = 3.28084;
const METERS_TO_MILES = 0.000621371;
const METERS_TO_KM = 0.001;
const SQ_METERS_TO_SQ_FEET = 10.7639;
const SQ_METERS_TO_SQ_MILES = 3.861e-7;
const SQ_METERS_TO_SQ_KM = 1e-6;
const SQ_METERS_TO_ACRES = 0.000247105;
const MPS_TO_MPH = 2.23694;
const MPS_TO_KMH = 3.6;

/** Get current unit preference from store */
export function getUnitPreference(): 'metric' | 'imperial' {
  return (store.getters as RootGetters)['userSettings/userSettings']?.account?.units ?? 'imperial';
}

interface UnitValue {
  value: number;
  unit: string;
}

/** Convert meters to the user's preferred distance unit value */
export function getDistanceValue(meters: number): UnitValue {
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

/** Convert meters to the user's preferred elevation unit value */
export function getElevationValue(meters: number): UnitValue {
  const units = getUnitPreference();
  
  if (units === 'metric') {
    return { value: meters, unit: 'm' };
  } else {
    return { value: meters * METERS_TO_FEET, unit: 'ft' };
  }
}

/** Format distance string based on user preference, e.g. "5.2 km" */
export function formatDistance(meters: number | null | undefined, decimals = 2): string {
  if (meters === null || meters === undefined) return 'N/A';
  const { value, unit } = getDistanceValue(meters);
  
  // Adjust decimals for smaller units to avoid excessive precision
  const adjustedDecimals = (unit === 'ft' || unit === 'm') ? 0 : decimals;
  
  return `${value.toFixed(adjustedDecimals)} ${unit}`;
}

/** Format elevation string based on user preference, e.g. "1,500 ft" */
export function formatElevation(meters: number | null | undefined): string {
  if (meters === null || meters === undefined) return 'N/A';
  const { value, unit } = getElevationValue(meters);
  // Round to integer and add thousands separators
  const roundedValue = Math.round(value);
  return `${roundedValue.toLocaleString('en-US')} ${unit}`;
}

/** Format area string based on user preference */
export function formatArea(sqMeters: number | null | undefined, decimals = 2): string {
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

/** Get raw multiplier for converting meters to user's preferred elevation unit. Useful for charts/graphs where we need uniform units. */
export function getElevationMultiplier(): number {
  return getUnitPreference() === 'imperial' ? METERS_TO_FEET : 1;
}

/** Get raw multiplier for converting meters to user's preferred large distance unit (km/mi). Useful for charts/graphs where we need uniform units. */
export function getDistanceMultiplier(): number {
  return getUnitPreference() === 'imperial' ? METERS_TO_MILES : METERS_TO_KM;
}

/** Get the label for the elevation unit: 'ft' or 'm' */
export function getElevationUnitLabel(): string {
  return getUnitPreference() === 'imperial' ? 'ft' : 'm';
}

/** Get the label for the large distance unit: 'mi' or 'km' */
export function getDistanceUnitLabel(): string {
  return getUnitPreference() === 'imperial' ? 'mi' : 'km';
}

/** Get raw multiplier for converting m/s to user's preferred speed unit. Useful for charts/graphs where we need uniform units. */
export function getSpeedMultiplier(): number {
  return getUnitPreference() === 'imperial' ? MPS_TO_MPH : MPS_TO_KMH;
}

/** Get the label for the speed unit: 'mph' or 'km/h' */
export function getSpeedUnitLabel(): string {
  return getUnitPreference() === 'imperial' ? 'mph' : 'km/h';
}

/** Format speed string based on user preference, e.g. "12.5 mph" or "20.1 km/h" */
export function formatSpeed(metersPerSecond: number | null | undefined, decimals = 1): string {
  if (metersPerSecond === null || metersPerSecond === undefined || isNaN(metersPerSecond)) return 'N/A';
  const multiplier = getSpeedMultiplier();
  const unit = getSpeedUnitLabel();
  const value = metersPerSecond * multiplier;
  return `${value.toFixed(decimals)} ${unit}`;
}

/** Format time duration in a human-readable format, e.g. "1h 23m 45s" or "45m 30s" or "30s" */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || isNaN(seconds) || seconds < 0) return 'N/A';
  
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const secs = Math.floor(seconds % 60);
  
  const parts: string[] = [];
  if (hours > 0) {
    parts.push(`${hours}h`);
  }
  if (minutes > 0) {
    parts.push(`${minutes}m`);
  }
  if (secs > 0 || parts.length === 0) {
    parts.push(`${secs}s`);
  }
  
  return parts.join(' ');
}

