<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-4">Map Settings</h2>

    <!-- Dynamically generated settings -->
    <div class="space-y-6 mb-6">
      <SettingsInput
        v-for="setting in getSettingsForSection('map')"
        :key="setting.key"
        :setting="setting"
        :model-value="settingsValues[setting.key]"
        :show-success="successCheckmarks[setting.key]"
        @update:model-value="handleSettingChange(setting.key, $event)"
      />
    </div>

    <!-- Account-level hidden features management (same UI pattern as map sidebar) -->
    <div class="mt-4 pt-4 border-t border-gray-200">
      <div class="flex items-center justify-between mb-2">
        <h3 class="text-md font-semibold text-gray-900">
          Hidden Features on Main Map
        </h3>
        <span class="text-xs text-gray-500">
          {{ hiddenFeatureIds.length }}
        </span>
      </div>
      <p class="text-sm text-gray-600 mb-3">
        These features are hidden only on your main map view. They remain visible in collections, tags, and public shares.
      </p>

      <HiddenFeaturesWidget
        :hidden-features="hiddenFeatureSummaries"
        :can-manage-hidden="true"
        :is-mobile="isMobile"
        :show-count="false"
        @unhide="unhideFeature"
        @unhide-all="unhideAll"
      />
    </div>
  </div>
</template>

<script>
import settingsConfig from "@/components/settings-data.json";
import SettingsMixin from "./mixins/SettingsMixin.js";
import SettingsInput from "./components/SettingsInput.vue";
import HiddenFeaturesWidget from "@/components/map/HiddenFeaturesWidget.vue";
import { clearHiddenFeatures } from "@/utils/userSettingsService.js";

export default {
  name: 'MapSettingsTab',
  components: {
    SettingsInput,
    HiddenFeaturesWidget,
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
      tileSources: [],
    }
  },
  computed: {
    hiddenFeatureIds() {
      const features = this.$store?.state?.hiddenFeatures || [];
      if (!Array.isArray(features)) return [];
      return features.map(f => String(f.id));
    },
    hiddenFeatureSummaries() {
      const features = this.$store?.state?.hiddenFeatures || [];
      if (!Array.isArray(features)) return [];
      return features.map(f => ({
        id: String(f.id),
        name: f.name || null,
        geometry_type: f.geometry_type || null,
      }));
    },
    isMobile() {
      // Simple viewport width check for mobile detection
      return window.innerWidth < 768;
    },
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
    },
    async unhideFeature(featureId) {
      // Import the debounced manager
      const hiddenFeaturesManager = (await import('@/utils/hiddenFeaturesManager.js')).default

      // Optimistic update: immediately update UI
      const optimisticUpdate = () => {
        this.$store.commit('removeHiddenFeature', String(featureId))
      }

      // Add to debounced bulk update with optimistic callback
      hiddenFeaturesManager.removeHidden(featureId, optimisticUpdate)
    },
    async unhideAll() {
      try {
        await clearHiddenFeatures();
        // Local cache: clear all hidden features in the store
        this.$store.commit("setHiddenFeatures", []);
      } catch (error) {
        console.error("Error clearing hidden features from settings:", error);
        if (this.toastRef) {
          this.toastRef.error(error.message || "Failed to clear hidden features.");
        }
      }
    },
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


