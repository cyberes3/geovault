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

<script setup lang="ts">
import { inject, onBeforeUnmount, onMounted, reactive, watch } from 'vue';
import type { PlatformStateBridge } from './types/platform-state';

/**
 * ==============================================================================
 * Extension Settings Component
 * ==============================================================================
 *
 * This component demonstrates how to create a settings tab for your extension.
 * It uses the platform's `platformState` bridge to read and save settings.
 *
 * Key concepts:
 * - `platformState` (injected by `createRouteWrapper`) is a narrow, read-mostly view of the
 *   current user's settings - not the raw Vuex store. See `main.ts`'s `wrap()` helper.
 * - Settings keys use dot notation: 'extensions.<your_ext_name>.<setting_key>'
 * - Changes are debounced and saved to the API automatically
 * - Visual feedback (checkmarks) confirm successful saves
 */

// `platformState` is provided per-route by `createRouteWrapper` (see main.ts). It's the only way
// extensions read/write user settings - there is no raw store access.
const platformState = inject('platformState') as PlatformStateBridge;

// Shared utilities that don't need a live store reference still come from window.gv_core.
const { loadSettingsFromValues, keyValueToNested } = window.gv_core.GeoVault.utils;
const toast = window.gv_core.GeoVault.toast;

// ==============================================================================
// Settings Configuration
// ==============================================================================
// Define all settings your extension manages.
// Keys MUST be prefixed with 'extensions.<your_ext_name>.' to save correctly.
const config = [
  { key: 'extensions.example_extension.verbose_logs', defaultValue: false },
  { key: 'extensions.example_extension.sync_interval', defaultValue: '30s' }
];

// ==============================================================================
// Reactive State
// ==============================================================================
const settingsValues = reactive<Record<string, unknown>>({});      // Current setting values (for v-model binding)
const successCheckmarks = reactive<Record<string, boolean>>({});   // Track which settings just saved successfully
const saveTimers: Record<string, ReturnType<typeof setTimeout>> = {}; // Debounce timers for each setting

// ==============================================================================
// Load Settings from platformState
// ==============================================================================
/**
 * Loads settings from the shared `platformState.userSettings` snapshot into local reactive
 * state, filling in defaults for anything not yet set.
 */
const load = (): void => {
  const values = loadSettingsFromValues(config, platformState.userSettings.value);
  Object.assign(settingsValues, values);
};

// ==============================================================================
// Save Settings to API
// ==============================================================================
/**
 * Handles setting changes: updates UI immediately, then saves to API (debounced).
 *
 * @param key   - Setting key in dot notation (e.g., 'extensions.example_extension.verbose_logs')
 * @param value - New value for the setting
 */
const handleSettingChange = (key: string, value: unknown): void => {
  // 1. Update UI immediately for instant feedback
  settingsValues[key] = value;

  // 2. Debounce API call (500ms) to avoid hammering the server
  if (saveTimers[key]) clearTimeout(saveTimers[key]);

  saveTimers[key] = setTimeout(() => {
    void (async () => {
      try {
        // 3. Convert dot notation to nested object for API
        // 'extensions.example_extension.verbose_logs' ->
        //   { extensions: { example_extension: { verbose_logs: value } } }
        const update = keyValueToNested(key, value) as Record<string, unknown>;

        // 4. Save to API and sync the shared settings cache in one call
        await platformState.saveUserSetting(update);

        // 5. Show success checkmark for 3 seconds
        successCheckmarks[key] = true;
        setTimeout(() => {
          successCheckmarks[key] = false;
        }, 3000);
      } catch (error) {
        // 6. Handle errors with toast notification
        toast.error(error instanceof Error ? error.message : 'Failed to save setting');
        // Revert to stored value on error
        load();
      }
    })();
  }, 500);
};

// ==============================================================================
// Lifecycle Hooks
// ==============================================================================
onMounted(() => {
  // Try to load settings immediately
  load();
});

// Watch for platformState updates and reload settings when they change
// This handles the case where settings arrive after component mount
watch(
  () => platformState.userSettings.value,
  () => {
    // Reload settings whenever the shared cache updates
    load();
  },
  { deep: true }
);

onBeforeUnmount(() => {
  // Cleanup: cancel any pending save timers
  Object.values(saveTimers).forEach(timer => { clearTimeout(timer); });
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
