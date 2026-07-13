<template>
  <div class="bg-white rounded-lg border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-6">Live Track Settings</h2>

    <div class="space-y-6">
      <SettingsInput
        :setting="{
          key: 'extensions.live_track.default_sort',
          type: 'select',
          title: 'Default Sort',
          description: 'How the track list is sorted when you open the Live Track page.',
          options: [
            { value: 'alphabetical', label: 'Alphabetical' },
            { value: 'last_updated', label: 'Last Updated' },
            { value: 'num_points', label: 'Number of Points' },
            { value: 'newest', label: 'Newest' }
          ]
        }"
        :model-value="settingsValues['extensions.live_track.default_sort']"
        :show-success="successCheckmarks['extensions.live_track.default_sort']"
        @update:model-value="handleSettingChange('extensions.live_track.default_sort', $event)"
      />
      <SettingsInput
        :setting="{
          key: 'extensions.live_track.default_map',
          type: 'select',
          title: 'Default Map',
          description: 'Default map layer when you open the Live Track page.',
          options: defaultMapOptions
        }"
        :model-value="settingsValues['extensions.live_track.default_map']"
        :show-success="successCheckmarks['extensions.live_track.default_map']"
        @update:model-value="handleSettingChange('extensions.live_track.default_map', $event)"
      />
    </div>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, inject, reactive, ref, computed, watch } from 'vue';

const { loadSettingsFromValues, keyValueToNested } = window.gv_core.GeoVault.utils;
/** @type {import('platform/extensions/platformState').PlatformStateBridge} */
const platformState = inject('platformState');

const config = [
  { key: 'extensions.live_track.default_sort', defaultValue: 'alphabetical' },
  { key: 'extensions.live_track.default_map', defaultValue: 'osm' }
];

const settingsValues = reactive({});
const successCheckmarks = reactive({});
const saveTimers = {};
const tileSources = ref([]);

const defaultMapOptions = computed(() => {
  if (tileSources.value.length === 0) {
    return [{ value: 'osm', label: 'OpenStreetMap' }];
  }
  return tileSources.value.map((s) => ({ value: s.id, label: s.name || s.id }));
});

async function fetchTileSources() {
  try {
    tileSources.value = await window.gv_core.tileSourceCatalog.load();
  } catch (e) {
    console.error('Live Track settings: fetch tile sources failed', e);
    tileSources.value = [{ id: window.gv_core.OSM_TILE_SOURCE_ID, name: 'OpenStreetMap' }];
  }
}

function load() {
  const values = loadSettingsFromValues(config, platformState.userSettings.value);
  Object.assign(settingsValues, values);
}

function handleSettingChange(key, value) {
  settingsValues[key] = value;
  if (saveTimers[key]) clearTimeout(saveTimers[key]);
  saveTimers[key] = setTimeout(async () => {
    try {
      const update = keyValueToNested(key, value);
      await platformState.saveUserSetting(update);
      successCheckmarks[key] = true;
      setTimeout(() => { successCheckmarks[key] = false; }, 3000);
    } catch (err) {
      window.gv_core?.GeoVault?.toast?.error(err.message || 'Failed to save setting');
      load();
    }
  }, 500);
}

onMounted(async () => {
  await fetchTileSources();
  load();
});
watch(() => platformState.userSettings.value, () => load(), { deep: true });
onBeforeUnmount(() => {
  Object.values(saveTimers).forEach(t => clearTimeout(t));
});
</script>
