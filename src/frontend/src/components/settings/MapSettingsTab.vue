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
import settingsConfig from "@/components/settings-data.json";
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
      settingsConfig: settingsConfig,
      tileSources: []
    }
  },
  async created() {
    // Load settings from store using mixin method
    this.loadSettingsFromStore();
    
    // Fetch tile sources to populate default_basemap options
    await this.fetchTileSources();
    this.populateBasemapOptions();
  },
  methods: {
    async fetchTileSources() {
      try {
        const response = await fetch('/api/tiles/sources/');
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        const data = await response.json();
        if (data.sources && Array.isArray(data.sources)) {
          this.tileSources = data.sources;
        }
      } catch (error) {
        console.error('Error fetching tile sources:', error);
        // Fallback to default OSM if API fails
        this.tileSources = [{
          id: 'osm',
          name: 'OpenStreetMap',
          type: 'osm',
          requires_proxy: false,
          client_config: {type: 'osm'}
        }];
      }
    },
    populateBasemapOptions() {
      // Find the default_basemap setting and populate its options
      const basemapSetting = this.settingsConfig.find(
        setting => setting.key === 'map.default_basemap'
      );
      
      if (basemapSetting && this.tileSources.length > 0) {
        // Populate options from tile sources
        basemapSetting.options = this.tileSources.map(source => ({
          value: source.id,
          label: source.name
        }));
      }
    }
  },
  watch: {
    // Watch for changes in the store and reload settings
    '$store.state.userSettings': {
      handler() {
        // Reload settings when store updates
        this.loadSettingsFromStore();
      },
      deep: true
    }
  }
}
</script>


