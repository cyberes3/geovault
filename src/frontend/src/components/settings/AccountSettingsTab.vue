<template>
  <div class="space-y-6">
    <PasswordChangeSection />
    <EmailVerificationSection />
    <DataExportSection />

    <!-- Account Settings Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Account Preferences</h2>

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

    <ApiKeysSection />
    <OAuthConnectionsSection />

    <!-- More Apps Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">More Apps</h2>
      <p class="text-sm text-gray-600 mb-4">
        Additional applications beyond those linked on the dashboard.
      </p>
      <a
        href="/api/pages/apps/"
        target="_blank"
        rel="noopener noreferrer"
        class="inline-flex items-center text-sm font-medium text-blue-600 hover:text-blue-800"
      >
        View Geovault App Directory
        <ArrowTopRightOnSquareIcon class="ml-1 w-4 h-4" />
      </a>
    </div>
  </div>
</template>

<script setup lang="ts">
import settingsConfig from '@/components/settings-data.json';
import SettingsInput from './components/SettingsInput.vue';
import PasswordChangeSection from './account/PasswordChangeSection.vue';
import EmailVerificationSection from './account/EmailVerificationSection.vue';
import ApiKeysSection from './account/ApiKeysSection.vue';
import OAuthConnectionsSection from './account/OAuthConnectionsSection.vue';
import DataExportSection from './account/DataExportSection.vue';
import { ArrowTopRightOnSquareIcon } from '@heroicons/vue/24/outline';
import { useSettingsSection, type SettingDefinition } from '@/composables/useSettingsSection';

const { sectionSettings, settingsValues, successCheckmarks, handleSettingChange } = useSettingsSection(
    settingsConfig as SettingDefinition[],
    'account',
);
</script>
