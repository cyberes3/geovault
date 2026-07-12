<template>
  <div class="space-y-6">
    <!-- Import Preferences Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Import Preferences</h2>

      <!-- Dynamically generated settings -->
      <div class="space-y-6">
        <SettingsInput
          v-for="setting in sectionSettings"
          :key="setting.key"
          :setting="setting"
          :model-value="settingsValues[setting.key]"
          :show-success="successCheckmarks[setting.key]"
          @update:model-value="handleSettingChange(setting.key, $event)"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import settingsConfig from '@/components/settings-data.json';
import SettingsInput from './components/SettingsInput.vue';
import { useSettingsSection, type SettingDefinition } from '@/composables/useSettingsSection';

const { sectionSettings, settingsValues, successCheckmarks, handleSettingChange } = useSettingsSection(
    settingsConfig as SettingDefinition[],
    'import',
);
</script>
