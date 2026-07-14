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

<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, reactive, watch } from 'vue';
import SettingsInput from 'platform/components/settings/components/SettingsInput.vue';
import { useTileSources } from '@/composables/useTileSources';
import { PLACES_DEFAULT_MAP_SOURCE_KEY } from '@/utils/placesMapSettings';
import type { PlatformStateBridge } from '@/types/platform-state';

const defaultMapSettingKey = PLACES_DEFAULT_MAP_SOURCE_KEY;

const { loadSettingsFromValues, keyValueToNested } = window.gv_core.GeoVault.utils;
const platformState = inject('platformState') as PlatformStateBridge;

const config = [
  { key: PLACES_DEFAULT_MAP_SOURCE_KEY, defaultValue: 'osm' }
];

const settingsValues = reactive<Record<string, unknown>>({});
const successCheckmarks = reactive<Record<string, boolean>>({});
const saveTimers: Record<string, ReturnType<typeof setTimeout>> = {};
const { baseSourceOptions, loadTileSources } = useTileSources();

const defaultMapOptions = computed(() => baseSourceOptions.value.map((row) => ({
  value: row.id,
  label: row.name,
})));

function load(): void {
  const values = loadSettingsFromValues(config, platformState.userSettings.value);
  Object.assign(settingsValues, values);
}

function handleSettingChange(key: string, value: unknown): void {
  settingsValues[key] = value;
  if (saveTimers[key]) clearTimeout(saveTimers[key]);
  saveTimers[key] = setTimeout(() => {
    void (async () => {
      try {
        const update = keyValueToNested(key, value) as Record<string, unknown>;
        await platformState.saveUserSetting(update);
        successCheckmarks[key] = true;
        setTimeout(() => {
          successCheckmarks[key] = false;
        }, 3000);
      } catch (err) {
        window.gv_core.GeoVault.toast.error(err instanceof Error ? err.message : 'Failed to save setting');
        load();
      }
    })();
  }, 500);
}

onMounted(() => {
  void (async () => {
    await loadTileSources();
    load();
  })();
});

watch(() => platformState.userSettings.value, () => { load(); }, { deep: true });

onBeforeUnmount(() => {
  Object.values(saveTimers).forEach((timer) => { clearTimeout(timer); });
});
</script>
