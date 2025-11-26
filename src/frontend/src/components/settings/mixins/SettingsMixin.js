import { updateUserSetting } from "@/utils/userSettingsService.js";

/**
 * Mixin for settings tabs that manage user settings with debounced saves
 * 
 * Provides:
 * - Reactive settingsValues object
 * - Success checkmarks tracking
 * - Debounced save operations
 * - Timer cleanup
 * 
 * Requires component to have:
 * - settingsConfig: Array of setting configuration objects
 * - toastRef: Toast component reference (optional, via props)
 * - $store: Vuex store instance
 */
export default {
  data() {
    return {
      // Reactive values for all settings
      settingsValues: {},
      // Track which settings were recently saved successfully
      successCheckmarks: {},
      // Debounce timers for save operations
      saveTimers: {}
    }
  },
  methods: {
    /**
     * Get settings for a specific section
     * @param {string} section - The section name to filter by
     * @returns {Array} - Filtered settings array
     */
    getSettingsForSection(section) {
      if (!this.settingsConfig) {
        console.warn('SettingsMixin: settingsConfig is not defined');
        return [];
      }
      return this.settingsConfig.filter(setting => setting.section === section);
    },

    /**
     * Convert dot-notation key to nested object path
     * @param {string} key - Dot notation key (e.g., "map.elevation_profile_source")
     * @returns {Array} - Array of path segments (e.g., ["map", "elevation_profile_source"])
     */
    keyToPath(key) {
      return key.split('.');
    },

    /**
     * Get value from nested object using dot-notation key
     * @param {Object} obj - Nested object
     * @param {string} key - Dot notation key (e.g., "map.elevation_profile_source")
     * @returns {any} - Value or undefined
     */
    getNestedValue(obj, key) {
      const path = this.keyToPath(key);
      let current = obj;
      for (const segment of path) {
        if (current === null || current === undefined) {
          return undefined;
        }
        current = current[segment];
      }
      return current;
    },

    /**
     * Convert dot-notation key and value to nested object
     * @param {string} key - Dot notation key (e.g., "map.elevation_profile_source")
     * @param {any} value - Value to set
     * @returns {Object} - Nested object (e.g., {"map": {"elevation_profile_source": value}})
     */
    keyValueToNested(key, value) {
      const path = this.keyToPath(key);
      const result = {};
      let current = result;
      
      for (let i = 0; i < path.length - 1; i++) {
        current[path[i]] = {};
        current = current[path[i]];
      }
      
      current[path[path.length - 1]] = value;
      return result;
    },

    /**
     * Load settings from Vuex store with defaults from configuration
     * @param {Array} config - Settings configuration array (optional, uses this.settingsConfig if not provided)
     */
    loadSettingsFromStore(config = null) {
      const settingsConfig = config || this.settingsConfig;
      if (!settingsConfig) {
        console.warn('SettingsMixin: settingsConfig is not defined');
        return;
      }

      // Settings are already loaded by App.vue into the Vuex store
      const settings = this.$store?.state?.userSettings || {};

      // Load all settings from configuration, using store values or defaults
      settingsConfig.forEach(setting => {
        const value = this.getNestedValue(settings, setting.key);
        // Use store value if available, otherwise use default
        // Only update if the value has actually changed to avoid unnecessary reactivity triggers
        const newValue = value !== undefined ? value : setting.defaultValue;
        if (this.settingsValues[setting.key] !== newValue) {
          this.settingsValues[setting.key] = newValue;
        }
      });
    },

    /**
     * Handle setting change - update value immediately and debounce save
     * @param {string} settingKey - The setting key
     * @param {any} value - The new value
     */
    handleSettingChange(settingKey, value) {
      // Update the value immediately for reactive UI
      this.settingsValues[settingKey] = value;
      // Debounce the save operation
      this.debouncedSave(settingKey, value);
    },

    /**
     * Debounced save operation
     * @param {string} settingKey - The setting key
     * @param {any} value - The value to save
     * @param {number} delay - Debounce delay in milliseconds (default: 500)
     */
    debouncedSave(settingKey, value, delay = 500) {
      // Clear existing timer for this setting
      if (this.saveTimers[settingKey]) {
        clearTimeout(this.saveTimers[settingKey]);
      }
      // Set new timer
      this.saveTimers[settingKey] = setTimeout(() => {
        this.saveSetting(settingKey, value);
      }, delay);
    },

    /**
     * Save setting to server
     * @param {string} settingKey - The setting key (dot notation)
     * @param {any} value - The value to save
     */
    async saveSetting(settingKey, value) {
      try {
        // Convert dot-notation key to nested object
        const nestedUpdate = this.keyValueToNested(settingKey, value);
        const response = await updateUserSetting(nestedUpdate);
        
        // Update local state immediately with the saved value from response
        if (response && response.settings) {
          const savedValue = this.getNestedValue(response.settings, settingKey);
          if (savedValue !== undefined) {
            this.settingsValues[settingKey] = savedValue;
          }
        }
        
        // Show success checkmark
        this.successCheckmarks[settingKey] = true;
        
        // Update store directly with settings from response (no need to fetch again)
        if (this.$store && response && response.settings) {
          this.$store.commit('userSettings', response.settings);
          // Reload settings from store to ensure UI is in sync
          this.loadSettingsFromStore();
        }
        
        // Hide checkmark after 3 seconds
        setTimeout(() => {
          this.successCheckmarks[settingKey] = false;
        }, 3000);
      } catch (error) {
        console.error(`Error saving setting ${settingKey}:`, error);
        const errorMessage = error.message || 'An error occurred while saving the setting.';
        
        // Revert to previous value on error
        // Reload from store to get the last known good value
        if (this.$store) {
          this.loadSettingsFromStore();
        }
        
        // Show error toast if available
        const toastRef = this.toastRef || this.$refs?.toast;
        if (toastRef) {
          toastRef.error(errorMessage);
        }
      }
    },

    /**
     * Cleanup all pending timers
     */
    cleanupTimers() {
      Object.values(this.saveTimers).forEach(timer => {
        if (timer) {
          clearTimeout(timer);
        }
      });
      this.saveTimers = {};
    },

    /**
     * Flush all pending saves immediately (without debounce)
     * This is called before component destruction to ensure changes are saved
     */
    flushPendingSaves() {
      Object.keys(this.saveTimers).forEach(settingKey => {
        // Clear the timer
        if (this.saveTimers[settingKey]) {
          clearTimeout(this.saveTimers[settingKey]);
          delete this.saveTimers[settingKey];
          // Get the current value and save it immediately (fire and forget)
          const value = this.settingsValues[settingKey];
          if (value !== undefined) {
            // Save immediately without debounce
            this.saveSetting(settingKey, value).catch(error => {
              console.error(`Error flushing save for ${settingKey}:`, error);
            });
          }
        }
      });
    }
  },
  beforeDestroy() {
    // Flush any pending saves immediately before destroying
    this.flushPendingSaves();
    // Cleanup any remaining timers
    this.cleanupTimers();
  }
}

