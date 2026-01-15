<template>
  <div class="space-y-6">
    <!-- Import Preferences Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Import Preferences</h2>

      <!-- Dynamically generated settings -->
      <div class="space-y-6">
        <SettingsInput
          v-for="setting in getSettingsForSection('import')"
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

<script>
import { ChevronDownIcon, Bars3Icon, XMarkIcon } from '@heroicons/vue/24/outline'
import settingsConfig from '@/components/settings-data.json'
import SettingsMixin from './mixins/SettingsMixin.js'
import SettingsInput from './components/SettingsInput.vue'

export default {
  name: 'ImportSettingsTab',
  components: {
    ChevronDownIcon,
    Bars3Icon,
    XMarkIcon,
    SettingsInput
  },
  mixins: [SettingsMixin],
  data() {
    return {
      // Settings configuration - loaded from external JSON file
      settingsConfig: settingsConfig,
    }
  },
  created() {
    // Load settings from store
    this.loadSettingsFromStore()
  },
  methods: {
  },
}
</script>
