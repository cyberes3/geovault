<template>
  <!-- 
    The settings tab is a standard Vue component. 
    We use Tailwind classes for styling (standardized to the platform).
  -->
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-6">Example Extension Settings</h2>
    
    <div class="space-y-6">
      <!-- 
        SettingsInput: A globally registered platform component.
        Use this for standard inputs (toggle, radio, text, etc.) 
        to ensure your settings look like native platform settings.
      -->
      <SettingsInput 
        :setting="{
          key: 'extensions.example_extension.verbose_logs',
          type: 'toggle',
          title: 'Verbose Debug Logs',
          description: 'Capture additional execution details in the browser console for developer troubleshooting.'
        }"
        :model-value="settingsValues['extensions.example_extension.verbose_logs']"
        :show-success="successCheckmarks['extensions.example_extension.verbose_logs']"
        @update:model-value="handleSettingChange('extensions.example_extension.verbose_logs', $event)"
      />

      <!-- Example of a custom settings layout using platform BaseButton -->
      <div class="pt-4 border-t border-gray-100">
        <div class="flex items-center gap-2 mb-3">
          <label class="block text-sm font-medium text-gray-700">Sync Interval</label>
          <Transition name="fade">
            <!-- Manual implementation of the success checkmark for custom layouts -->
            <svg v-if="successCheckmarks['extensions.example_extension.sync_interval']" class="h-5 w-5 text-green-600" fill="currentColor" viewBox="0 0 20 20">
              <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
            </svg>
          </Transition>
        </div>
        <p class="text-sm text-gray-500 mb-4">How frequently should the dashboard refresh the remote data?</p>
        <div class="flex gap-2">
          <!-- BaseButton is also globally registered -->
          <BaseButton 
            v-for="t in ['5s', '30s', '1m', '5m']" 
            :key="t" 
            @click="handleSettingChange('extensions.example_extension.sync_interval', t)"
            :variant="settingsValues['extensions.example_extension.sync_interval'] === t ? 'primary' : 'white'"
            size="sm"
          >
            {{ t }}
          </BaseButton>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, reactive } from 'vue';

/*
  SHARED UTILITIES
  By using window.GeoVault, we ensure we use the exact same logic and reactive
  state as the main platform.
*/
const { updateUserSetting, loadSettingsFromStore, keyValueToNested } = window.GeoVault.utils;
const toast = window.GeoVault.toast;

/**
 * Access the main application store.
 */
const getStore = () => {
    return window.store || (window.Vuex && window.Vuex.useStore ? window.Vuex.useStore() : null);
};

const store = getStore();

// Reactive state for UI data binding
const settingsValues = reactive({});
const successCheckmarks = reactive({});
const saveTimers = {};

/**
 * Configuration for the settings we want to manage.
 * Keys MUST be prefixed with 'extensions.<your_ext_name>.' to save correctly.
 */
const config = [
  { key: 'extensions.example_extension.verbose_logs', defaultValue: false },
  { key: 'extensions.example_extension.sync_interval', defaultValue: '30s' }
];

/**
 * Load initial values from the store (which the platform loads from the DB on boot).
 */
const load = () => {
  if (!store) {
    console.error('[Example Extension] Store not found');
    return;
  }
  const values = loadSettingsFromStore(config, store);
  Object.assign(settingsValues, values);
};

/**
 * Persist changes back to the database.
 * 
 * @param {string} key   - The setting key (dot notation).
 * @param {any}    value - The new value.
 */
const handleSettingChange = (key, value) => {
  // 1. Update local UI immediately for responsiveness
  settingsValues[key] = value;
  
  // 2. Debounce the API call (prevents hammering the server if the user clicks quickly)
  if (saveTimers[key]) clearTimeout(saveTimers[key]);
  
  saveTimers[key] = setTimeout(async () => {
    try {
      // 3. Convert dot-notation to nested JSON object
      // 'extensions.ext.key' -> { extensions: { ext: { key: value } } }
      const update = keyValueToNested(key, value);

      // 4. Save to the main platform settings API
      const response = await updateUserSetting(update);
      
      if (response && response.success) {
        // 5. Update the global store so other components see the change
        if (store) store.commit('userSettings', response.settings);
        
        // 6. Provide visual feedback (the small green checkmark)
        successCheckmarks[key] = true;
        setTimeout(() => {
          successCheckmarks[key] = false;
        }, 3000);
      }
    } catch (error) {
      // 7. Error handling with the platform's toast system
      if (toast) toast.error(error.message || 'Failed to save setting');
      load(); // Revert local state on failure
    }
  }, 500);
};

onMounted(() => {
  load();
});

onBeforeUnmount(() => {
  // Always cleanup timers to avoid memory leaks or unexpected behavior
  Object.values(saveTimers).forEach(t => clearTimeout(t));
});
</script>

<style scoped>
/* Scoped styles for micro-animations */
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.3s;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}
</style>
