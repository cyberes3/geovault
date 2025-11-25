<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-4">Map Settings</h2>

    <!-- Dynamically generated settings -->
    <div class="space-y-6">
      <SettingsInput
        v-for="setting in getSettingsForSection('map')"
        :key="setting.key"
        :setting="setting"
        :model-value="settingsValues[setting.key]"
        :show-success="successCheckmarks[setting.key]"
        @update:model-value="handleSettingChange(setting.key, $event)"
      />
    </div>
  </div>
</template>

<script>
import settingsConfig from "@/components/settings-map.json";
import SettingsMixin from "./mixins/SettingsMixin.js";
import SettingsInput from "./components/SettingsInput.vue";

export default {
  name: 'MapSettingsTab',
  components: {
    SettingsInput
  },
  mixins: [SettingsMixin],
  props: {
    toastRef: {
      type: Object,
      default: null
    }
  },
  data() {
    return {
      // Settings configuration - loaded from external JSON file
      settingsConfig: settingsConfig
    }
  },
  created() {
    // Load settings from store using mixin method
    this.loadSettingsFromStore();
  }
}
</script>


