import axios from "axios";
import { getCookie } from "@/assets/js/auth.js";
import { getNestedValue } from "@/utils/settingsUtils.js";

/**
 * Update user settings on the server with a partial nested JSON object
 * @param {Object} settingsUpdate - Partial nested settings object (e.g., {"map": {"elevation_profile_source": "api"}})
 * @returns {Promise<Object>} - Response data with success status and settings
 * @throws {Error} - If the request fails
 */
export async function updateUserSetting(settingsUpdate) {
  try {
    const response = await axios.put('/api/user/settings/update/', settingsUpdate, {
      headers: {
        'X-CSRFToken': getCookie('csrftoken'),
        'Content-Type': 'application/json'
      }
    });

    if (response.status === 200 && response.data.settings) {
      return {
        success: true,
        settings: response.data.settings
      };
    } else {
      throw new Error(response.data?.error || 'Failed to save setting.');
    }
  } catch (error) {
    // Re-throw with a more descriptive error message
    if (error.response?.data?.error) {
      throw new Error(error.response.data.error);
    } else if (error.message) {
      throw error;
    } else {
      throw new Error('An error occurred while saving the setting.');
    }
  }
}

/**
 * Clear all hidden feature IDs for the current account.
 * Frontend keeps a local cache, so the backend only returns a status code.
 * @returns {Promise<void>}
 */
export async function clearHiddenFeatures() {
  try {
    const response = await axios.post(
      '/api/user/settings/hidden-features/clear/',
      {},
      {
        headers: {
          'X-CSRFToken': getCookie('csrftoken'),
          'Content-Type': 'application/json',
        },
      },
    );

    if (response.status >= 200 && response.status < 300) {
      return;
    }
    throw new Error(response.data?.error || 'Failed to clear hidden features.');
  } catch (error) {
    if (error.response?.data?.error) {
      throw new Error(error.response.data.error);
    }
    throw error;
  }
}

/**
 * Get settings for a specific section from configuration
 * @param {Array} config - Settings configuration array
 * @param {string} section - The section name to filter by
 * @returns {Array} - Filtered settings array
 */
export function getSettingsForSection(config, section) {
  if (!Array.isArray(config)) {
    console.warn('getSettingsForSection: config must be an array');
    return [];
  }
  return config.filter(setting => setting.section === section);
}

/**
 * Load settings from Vuex store with defaults from configuration
 * @param {Array} config - Settings configuration array
 * @param {Object} store - Vuex store instance (or store state)
 * @returns {Object} - Object with setting keys as properties and values from store or defaults
 */
export function loadSettingsFromStore(config, store) {
  if (!Array.isArray(config)) {
    console.warn('loadSettingsFromStore: config must be an array');
    return {};
  }

  // Handle both store object and store state
  const settings = store?.state?.userSettings || store?.userSettings || store || {};
  const settingsValues = {};

  // Load all settings from configuration, using store values or defaults
  config.forEach(setting => {
    const value = getNestedValue(settings, setting.key);
    settingsValues[setting.key] = value !== undefined
      ? value
      : setting.defaultValue;
  });

  return settingsValues;
}
