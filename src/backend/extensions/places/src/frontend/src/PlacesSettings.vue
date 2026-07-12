<template>
  <div class="bg-white rounded-lg border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-6">Places Settings</h2>

    <div class="space-y-6">
      <SettingsInput
          :setting="{
            key: defaultMapSettingKey,
            type: 'select',
            title: 'Default Map',
            description: 'Default basemap when you open the Places list or add or edit a place.',
            options: defaultMapOptions
          }"
          :model-value="settingsValues[defaultMapSettingKey]"
          :show-success="successCheckmarks[defaultMapSettingKey]"
          @update:model-value="handleSettingChange(defaultMapSettingKey, $event)"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import SettingsInput from 'platform/components/parts/SettingsInput.vue';
import { useTileSources } from '@/composables/useTileSources.js';
import { PLACES_DEFAULT_MAP_SOURCE_KEY } from '@/utils/placesMapSettings.js';

const defaultMapSettingKey = PLACES_DEFAULT_MAP_SOURCE_KEY;

const { updateUserSetting, loadSettingsFromStore, keyValueToNested } = window.gv_core.GeoVault.utils;
const store = window.gv_core?.store || null;

const config = [
  { key: PLACES_DEFAULT_MAP_SOURCE_KEY, defaultValue: 'osm' }
];

const settingsValues = reactive({});
const successCheckmarks = reactive({});
const saveTimers = {};
const { baseSourceOptions, loadTileSources } = useTileSources();

const defaultMapOptions = computed(() => baseSourceOptions.value.map((row) => ({
  value: row.id,
  label: row.name,
})));

function load() {
  if (!store?.getters?.['userSettings/userSettings']) return;
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
        store.dispatch('userSettings/setUserSettings', response.settings);
        successCheckmarks[key] = true;
        setTimeout(() => {
          successCheckmarks[key] = false;
        }, 3000);
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
  await loadTileSources();
  load();
});

watch(() => store?.getters?.['userSettings/userSettings'], () => load(), { deep: true });

onBeforeUnmount(() => {
  Object.values(saveTimers).forEach((timer) => clearTimeout(timer));
});
</script>
