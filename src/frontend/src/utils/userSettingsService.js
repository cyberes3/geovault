import axios from "axios";
import { getCookie } from "@/assets/js/auth.js";

/**
 * Update a user setting on the server
 * @param {string} key - The setting key to update
 * @param {any} value - The setting value
 * @returns {Promise<Object>} - Response data with success status and settings
 * @throws {Error} - If the request fails
 */
export async function updateUserSetting(key, value) {
  try {
    const response = await axios.put('/api/data/user/settings/update/', {
      key: key,
      value: value
    }, {
      headers: {
        'X-CSRFToken': getCookie('csrftoken'),
        'Content-Type': 'application/json'
      }
    });

    if (response.data.success) {
      return {
        success: true,
        settings: response.data.settings,
        updated_at: response.data.updated_at
      };
    } else {
      throw new Error(response.data.error || 'Failed to save setting.');
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
    settingsValues[setting.key] = settings[setting.key] !== undefined 
      ? settings[setting.key] 
      : setting.defaultValue;
  });

  return settingsValues;
}

