/**
 * Dynamic formatters for live-track point params. Uses account unit preferences
 * from the store (metric/imperial) and applies type-specific formatting.
 */

const METERS_TO_FEET = 3.28084;
const METERS_TO_MILES = 0.000621371;
const METERS_TO_KM = 0.001;
const KMH_TO_MPH = 0.621371;

function getUnitPreference() {
  return window.gv_core?.store?.state?.userSettings?.account?.units || 'imperial';
}

/**
 * Format timestamp for list/params display (locale string).
 * Accepts ms (number) or seconds (number < 1e12).
 */
export function formatTimestampLocal(ms) {
  if (ms == null || ms === '') return '';
  const d = new Date(typeof ms === 'number' && ms < 1e12 ? ms * 1000 : ms);
  return d.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    second: '2-digit'
  });
}

/**
 * Format a single param value for display. Uses key to apply special formatting
 * (units, degrees, percent, yes/no, etc.). Falls back to string/JSON for unknown keys.
 * @param {string} key - Param key (e.g. 'alt', 'acc', 'spd_kph')
 * @param {*} value - Raw value from point_params
 * @returns {string} Formatted display string
 */
export function formatParamDisplay(key, value) {
  if (value === null || value === undefined) return '';
  const units = getUnitPreference();

  switch (key) {
    case 'alt': {
      const m = Number(value);
      if (!Number.isFinite(m)) return String(value);
      if (units === 'metric') return `${Math.round(m)} m`;
      return `${Math.round(m * METERS_TO_FEET)} ft`;
    }
    case 'acc': {
      const m = Number(value);
      if (!Number.isFinite(m)) return String(value);
      if (units === 'metric') return `${Math.round(m)} m`;
      return `${Math.round(m * METERS_TO_FEET)} ft`;
    }
    case 'bearing': {
      const n = Number(value);
      if (!Number.isFinite(n)) return String(value);
      return `${Math.round(n)}°`;
    }
    case 'prov':
      return String(value).toUpperCase();
    case 'spd_kph': {
      const kph = Number(value);
      if (!Number.isFinite(kph)) return String(value);
      if (units === 'metric') return `${Math.round(kph)} km/h`;
      return `${Math.round(kph * KMH_TO_MPH)} mph`;
    }
    case 'starttimestamp':
      return formatTimestampLocal(value);
    case 'batt': {
      const n = Number(value);
      if (!Number.isFinite(n)) return String(value);
      return `${Math.round(n)}%`;
    }
    case 'ischarging':
      return value === true || value === 'true' || value === '1' ? 'Yes' : 'No';
    case 'dist': {
      const m = Number(value);
      if (!Number.isFinite(m)) return String(value);
      if (units === 'metric') {
        if (m > 1000) return `${Math.round(m * METERS_TO_KM)} km`;
        return `${Math.round(m)} m`;
      }
      const mi = m * METERS_TO_MILES;
      if (mi > 0.1) return `${Math.round(mi * 10) / 10} mi`;
      return `${Math.round(m * METERS_TO_FEET)} ft`;
    }
    default:
      if (typeof value === 'object') return JSON.stringify(value);
      return String(value);
  }
}
