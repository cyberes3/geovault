<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-6">Live Track Settings</h2>

    <div class="space-y-6">
      <SettingsInput
        :setting="{
          key: 'extensions.live_track.default_sort',
          type: 'select',
          title: 'Default sort',
          description: 'How the track list is sorted when you open the Live Track page.',
          options: [
            { value: 'alphabetical', label: 'Alphabetical' },
            { value: 'last_updated', label: 'Last updated' },
            { value: 'num_points', label: 'Number of points' },
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
          title: 'Default map',
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
import { onMounted, onBeforeUnmount, reactive, ref, computed, watch } from 'vue';

const { updateUserSetting, loadSettingsFromStore, keyValueToNested } = window.gv_core.GeoVault.utils;
const store = window.gv_core?.store || null;

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
    const response = await fetch('/api/tiles/sources/');
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    if (data.sources && Array.isArray(data.sources)) {
      tileSources.value = data.sources.filter((s) => !s.hidden);
    }
    if (tileSources.value.length === 0) {
      tileSources.value = [{ id: 'osm', name: 'OpenStreetMap' }];
    }
  } catch (e) {
    console.error('Live Track settings: fetch tile sources failed', e);
    tileSources.value = [{ id: 'osm', name: 'OpenStreetMap' }];
  }
}

function load() {
  if (!store?.state?.userSettings) return;
  const values = loadSettingsFromStore(config, store);
  Object.assign(settingsValues, values);
}

function handleSettingChange(key, value) {
  settingsValues[key] = value;
  if (saveTimers[key]) clearTimeout(saveTimers[key]);
  saveTimers[key] = setTimeout(async () => {
    try {
      const update = keyValueToNested(key, value);
      const response = await updateUserSetting(update);
      if (response?.success && store) {
        store.commit('userSettings', response.settings);
        successCheckmarks[key] = true;
        setTimeout(() => { successCheckmarks[key] = false; }, 3000);
      }
    } catch (err) {
      if (window.gv_core?.GeoVault?.toast) {
        window.gv_core.GeoVault.toast.error(err.message || 'Failed to save setting');
      }
      load();
    }
  }, 500);
}

onMounted(async () => {
  await fetchTileSources();
  load();
});
watch(() => store?.state?.userSettings, () => load(), { deep: true });
onBeforeUnmount(() => {
  Object.values(saveTimers).forEach(t => clearTimeout(t));
});
</script>
