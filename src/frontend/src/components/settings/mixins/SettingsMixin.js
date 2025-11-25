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
        this.settingsValues[setting.key] = settings[setting.key] !== undefined 
          ? settings[setting.key] 
          : setting.defaultValue;
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
     * @param {string} settingKey - The setting key
     * @param {any} value - The value to save
     */
    async saveSetting(settingKey, value) {
      try {
        await updateUserSetting(settingKey, value);
        
        // Show success checkmark
        this.successCheckmarks[settingKey] = true;
        
        // Refresh cached settings in the store
        if (this.$store) {
          await this.$store.dispatch('fetchUserSettings');
        }
        
        // Hide checkmark after 3 seconds
        setTimeout(() => {
          this.successCheckmarks[settingKey] = false;
        }, 3000);
      } catch (error) {
        console.error(`Error saving setting ${settingKey}:`, error);
        const errorMessage = error.message || 'An error occurred while saving the setting.';
        
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
    }
  },
  beforeDestroy() {
    // Cleanup timers when component is destroyed
    this.cleanupTimers();
  }
}

