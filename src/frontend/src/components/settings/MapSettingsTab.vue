<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-4">Map Settings</h2>

    <!-- Dynamically generated settings -->
    <div class="space-y-6 mb-6">
      <SettingsInput
        v-for="setting in sectionSettings"
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

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useStore } from 'vuex';
import settingsConfig from '@/components/settings-data.json';
import SettingsInput from './components/SettingsInput.vue';
import HiddenFeaturesWidget from '@/components/map/HiddenFeaturesWidget.vue';
import { clearHiddenFeatures } from '@/utils/userSettingsService';
import { tileSourceCatalog } from '@/utils/map/openlayers';
import hiddenFeaturesManager from '@/utils/hiddenFeaturesManager';
import { toastApiError } from '@/utils/apiError';
import { useSettingsSection, type SettingDefinition } from '@/composables/useSettingsSection';
import type { HiddenFeature } from '@/assets/js/store/modules/userSettings';

/** Shape returned by the (untyped, plain-JS) `tileSourceCatalog.load()`. */
interface TileSource {
    id: string;
    name: string;
    type: string;
    requires_proxy: boolean;
    client_config?: { type: string; attribution?: string };
}

interface RootGetters {
    'userSettings/hiddenFeatures': HiddenFeature[];
}

const store = useStore();

const { sectionSettings, settingsValues, successCheckmarks, handleSettingChange } = useSettingsSection(
    settingsConfig as SettingDefinition[],
    'map',
);

const tileSources = ref<TileSource[]>([]);

const hiddenFeatureIds = computed<string[]>(() => {
    const getters = store.getters as RootGetters;
    return getters['userSettings/hiddenFeatures'].map((f) => String(f.id));
});

const hiddenFeatureSummaries = computed(() => {
    const getters = store.getters as RootGetters;
    return getters['userSettings/hiddenFeatures'].map((f) => ({
        id: String(f.id),
        name: f.name ?? null,
        geometry_type: f.geometry_type ?? null,
    }));
});

// Simple viewport width check for mobile detection
const isMobile = computed(() => window.innerWidth < 768);

const tileSourcesWithAttribution = computed(() => {
    return tileSources.value
        .map((source) => ({
            id: source.id,
            name: source.name || source.id,
            attribution: processAttributionLinks(source.client_config?.attribution || 'No attribution available'),
        }))
        .sort((a, b) => a.name.localeCompare(b.name));
});

function processAttributionLinks(html: string): string {
    if (!html || typeof html !== 'string') {
        return html;
    }

    // Process all <a> tags to add target="_blank" and rel="noopener noreferrer"
    return html.replace(/<a(\s+[^>]*|)>/gi, (_match, attributes: string) => {
        // Normalize attributes - handle case where there's no space after <a
        let attrs = (attributes || '').trim();

        // Check if target already exists
        if (attrs.includes('target=')) {
            attrs = attrs.replace(/target=["'][^"']*["']/gi, 'target="_blank"');
        } else {
            attrs = attrs ? `${attrs} target="_blank"` : 'target="_blank"';
        }

        // Check if rel already exists
        if (attrs.includes('rel=')) {
            const relMatch = /rel=["']([^"']*)["']/i.exec(attrs);
            if (relMatch) {
                const existingRel = relMatch[1];
                if (!existingRel.includes('noopener') || !existingRel.includes('noreferrer')) {
                    const newRel = existingRel
                        .split(/\s+/)
                        .concat(['noopener', 'noreferrer'])
                        .filter((v, i, a) => a.indexOf(v) === i)
                        .join(' ');
                    attrs = attrs.replace(/rel=["'][^"']*["']/gi, `rel="${newRel}"`);
                }
            }
        } else {
            attrs = attrs ? `${attrs} rel="noopener noreferrer"` : 'rel="noopener noreferrer"';
        }

        return attrs ? `<a ${attrs}>` : '<a>';
    });
}

async function fetchTileSources(): Promise<void> {
    try {
        // Shared catalog already filters out hidden (utility) sources and caches the result.
        // `tileSourceCatalog` is plain JS and untyped, hence the cast.
        tileSources.value = await tileSourceCatalog.load() as TileSource[];
    } catch (error) {
        console.error('Error fetching tile sources:', error);
        // Fallback to default OSM if API fails
        tileSources.value = [{
            id: 'osm',
            name: 'OpenStreetMap',
            type: 'osm',
            requires_proxy: false,
            client_config: { type: 'osm' },
        }];
    }
}

function populateBasemapOptions(): void {
    // Find the default_basemap setting and populate its options
    const basemapSetting = (settingsConfig as SettingDefinition[]).find(
        (setting) => setting.key === 'map.default_basemap',
    );

    if (basemapSetting && tileSources.value.length > 0) {
        basemapSetting.options = tileSources.value.map((source) => ({
            value: source.id,
            label: source.name,
        }));
    }
}

async function unhideFeature(featureId: string): Promise<void> {
    const optimisticUpdate = () => {
        void store.dispatch('userSettings/removeHiddenFeature', String(featureId));
    };

    hiddenFeaturesManager.removeHidden(featureId, optimisticUpdate);
    try {
        await hiddenFeaturesManager.forceFlush();
    } catch (error) {
        console.error('Error unhiding feature from settings:', error);
        toastApiError(error, 'Failed to unhide feature');
    }
}

async function unhideAll(): Promise<void> {
    try {
        await clearHiddenFeatures();
        // Local cache: clear all hidden features in the store
        void store.dispatch('userSettings/setHiddenFeatures', []);
    } catch (error) {
        console.error('Error clearing hidden features from settings:', error);
        toastApiError(error, 'Failed to clear hidden features');
    }
}

onMounted(async () => {
    await fetchTileSources();
    populateBasemapOptions();
});
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
