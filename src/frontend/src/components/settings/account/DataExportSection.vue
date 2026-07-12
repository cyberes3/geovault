<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-4">Data Export</h2>
    <p class="text-sm text-gray-600 mb-4">
      Download all your features and associated data as a single KMZ file. This file can be opened in Google Earth or
      other GIS software.
    </p>

    <BaseButton
        @click="handleDownloadFeatures"
        :disabled="downloadLoading"
        variant="primary"
        color="blue"
        size="sm"
        title="Download All Features as KMZ"
    >
      <span v-if="downloadLoading">Preparing Download...</span>
      <span v-else>Download All Features (KMZ)</span>
    </BaseButton>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import BaseButton from '@/components/parts/BaseButton.vue';
import { exportFeaturesKmz } from '@/api/services/featuresApi';
import { toastApiError } from '@/utils/apiError';

const downloadLoading = ref(false);

async function handleDownloadFeatures(): Promise<void> {
  downloadLoading.value = true;

  try {
    const { blob, filename } = await exportFeaturesKmz(true);
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', filename ?? 'all-features.kmz');
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  } catch (error) {
    toastApiError(error, 'An error occurred during download.');
  } finally {
    downloadLoading.value = false;
  }
}
</script>
