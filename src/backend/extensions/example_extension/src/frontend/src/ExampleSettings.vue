<template>
  <div class="bg-white rounded-lg border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-6">Example Extension Settings</h2>

    <div class="space-y-6">
      <SettingsInput
        :setting="{
          key: 'extensions.example_extension.verbose_logs',
          type: 'toggle',
          title: 'Verbose Debug Logs',
          description: 'Capture additional execution details in the browser console for developer troubleshooting.'
        }"
        :model-value="settingsValues.verbose_logs"
        :show-success="successCheckmarks.verbose_logs"
        @update:model-value="handleSettingChange('verbose_logs', $event)"
      />

      <div class="pt-4 border-t border-gray-100">
        <div class="flex items-center gap-2 mb-3">
          <label class="block text-sm font-medium text-gray-700">Sync Interval</label>
          <Transition name="fade">
            <svg v-if="successCheckmarks.sync_interval" class="h-5 w-5 text-green-600" fill="currentColor" viewBox="0 0 20 20">
              <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
            </svg>
          </Transition>
        </div>
        <p class="text-sm text-gray-500 mb-4">How frequently should the dashboard refresh the remote data?</p>
        <div class="flex gap-2">
          <BaseButton
            v-for="t in ['5s', '30s', '1m', '5m']"
            :key="t"
            @click="handleSettingChange('sync_interval', t)"
            :variant="settingsValues.sync_interval === t ? 'primary' : 'white'"
            size="sm"
          >
            {{ t }}
          </BaseButton>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive } from 'vue';

const EXTENSION_NAME = 'example_extension';
const settingsApi = window.gv_core.settings.useExtensionSettings(EXTENSION_NAME);
const toast = window.gv_core.ui.toast;

const settingsValues = reactive({
  verbose_logs: false as unknown,
  sync_interval: '30s' as unknown,
});
const successCheckmarks = reactive<Record<string, boolean>>({});
const saveTimers: Record<string, ReturnType<typeof setTimeout>> = {};

const load = (): void => {
  settingsValues.verbose_logs = settingsApi.get('verbose_logs') ?? false;
  settingsValues.sync_interval = settingsApi.get('sync_interval') ?? '30s';
};

const handleSettingChange = (key: 'verbose_logs' | 'sync_interval', value: unknown): void => {
  settingsValues[key] = value;
  if (saveTimers[key]) clearTimeout(saveTimers[key]);
  saveTimers[key] = setTimeout(() => {
    void (async () => {
      try {
        await settingsApi.save(key, value);
        successCheckmarks[key] = true;
        setTimeout(() => {
          successCheckmarks[key] = false;
        }, 3000);
      } catch (error) {
        toast.error(error instanceof Error ? error.message : 'Failed to save setting');
        load();
      }
    })();
  }, 500);
};

onMounted(() => {
  load();
});
</script>

<style scoped>
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.3s;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}
</style>
