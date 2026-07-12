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

    <!-- Map Layer Attributions -->
    <div class="mt-6 pt-6 border-t border-gray-200 attribution-section">
      <h3 class="text-sm font-semibold text-gray-700 mb-2">
        Map Layer Attributions
      </h3>
      <div class="space-y-1.5">
        <div
          v-for="source in tileSourcesWithAttribution"
          :key="source.id"
          class="text-xs text-gray-600"
        >
          <span class="font-medium text-gray-700">{{ source.name }}:</span>
          <span class="ml-1" v-html="source.attribution"></span>
        </div>
        <div v-if="tileSourcesWithAttribution.length === 0" class="text-xs text-gray-500 italic">
          No map sources available.
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import settingsConfig from "@/components/settings-data.json";
import SettingsMixin from "./mixins/SettingsMixin.js";
import SettingsInput from "./components/SettingsInput.vue";
import HiddenFeaturesWidget from "@/components/map/HiddenFeaturesWidget.vue";
import { clearHiddenFeatures } from "@/utils/userSettingsService";
import { tileSourceCatalog } from "@/utils/map/openlayers";
import { toast } from '@/utils/toast'
import { toastApiError } from '@/utils/apiError'

export default {
  name: 'MapSettingsTab',
  components: {
    SettingsInput,
    HiddenFeaturesWidget,
  },
  mixins: [SettingsMixin],
  data() {
    return {
      // Settings configuration - loaded from external JSON file
      settingsConfig: settingsConfig,
      tileSources: [],
    }
  },
  computed: {
    storeUserSettings() {
      return this.$store?.getters?.['userSettings/userSettings'];
    },
    hiddenFeatureIds() {
      const features = this.$store?.getters?.['userSettings/hiddenFeatures'] || [];
      if (!Array.isArray(features)) return [];
      return features.map(f => String(f.id));
    },
    hiddenFeatureSummaries() {
      const features = this.$store?.getters?.['userSettings/hiddenFeatures'] || [];
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
    tileSourcesWithAttribution() {
      // Return tile sources with their attributions, sorted by name
      return this.tileSources
        .map(source => ({
          id: source.id,
          name: source.name || source.id,
          attribution: this.processAttributionLinks(source.client_config?.attribution || 'No attribution available')
        }))
        .sort((a, b) => a.name.localeCompare(b.name));
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
    processAttributionLinks(html) {
      if (!html || typeof html !== 'string') {
        return html;
      }
      
      // Process all <a> tags to add target="_blank" and rel="noopener noreferrer"
      return html.replace(/<a(\s+[^>]*|)>/gi, (match, attributes) => {
        // Normalize attributes - handle case where there's no space after <a
        let attrs = (attributes || '').trim();
        
        // Check if target already exists
        if (attrs.includes('target=')) {
          // Replace existing target
          attrs = attrs.replace(/target=["'][^"']*["']/gi, 'target="_blank"');
        } else {
          // Add target attribute
          attrs = attrs ? attrs + ' target="_blank"' : 'target="_blank"';
        }
        
        // Check if rel already exists
        if (attrs.includes('rel=')) {
          // Add noopener noreferrer to existing rel if not present
          const relMatch = attrs.match(/rel=["']([^"']*)["']/i);
          if (relMatch) {
            const existingRel = relMatch[1];
            if (!existingRel.includes('noopener') || !existingRel.includes('noreferrer')) {
              const newRel = existingRel.split(/\s+/).concat(['noopener', 'noreferrer']).filter((v, i, a) => a.indexOf(v) === i).join(' ');
              attrs = attrs.replace(/rel=["'][^"']*["']/gi, `rel="${newRel}"`);
            }
          }
        } else {
          // Add rel attribute
          attrs = attrs ? attrs + ' rel="noopener noreferrer"' : 'rel="noopener noreferrer"';
        }
        
        return attrs ? `<a ${attrs}>` : '<a>';
      });
    },
    async fetchTileSources() {
      try {
        // Shared catalog already filters out hidden (utility) sources and caches the result.
        this.tileSources = await tileSourceCatalog.load();
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
      const hiddenFeaturesManager = (await import('@/utils/hiddenFeaturesManager')).default

      const optimisticUpdate = () => {
        this.$store.dispatch('userSettings/removeHiddenFeature', String(featureId))
      }

      hiddenFeaturesManager.removeHidden(featureId, optimisticUpdate)
      try {
        await hiddenFeaturesManager.forceFlush()
      } catch (error) {
        console.error('Error unhiding feature from settings:', error)
        toastApiError(error, 'Failed to unhide feature')
      }
    },
    async unhideAll() {
      try {
        await clearHiddenFeatures();
        // Local cache: clear all hidden features in the store
        this.$store.dispatch("userSettings/setHiddenFeatures", []);
      } catch (error) {
        console.error("Error clearing hidden features from settings:", error);
        toastApiError(error, 'Failed to clear hidden features');
      }
    },
  },
  watch: {
    // Watch for changes in the store and reload settings
    storeUserSettings: {
      handler() {
        // Reload settings when store updates
        this.loadSettingsFromStore();
      },
      deep: true
    }
  }
}
</script>

<style scoped>
.attribution-section :deep(a) {
  color: var(--color-blue-600);
  text-decoration: underline;
  transition: color 0.2s ease;
}

.attribution-section :deep(a:hover) {
  color: var(--color-blue-700);
  text-decoration: underline;
}

.attribution-section :deep(a:visited) {
  color: var(--color-blue-700);
}

.attribution-section :deep(a:visited:hover) {
  color: var(--color-blue-800);
}
</style>

