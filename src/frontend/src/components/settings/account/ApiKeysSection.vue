<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-4">API Keys</h2>
    <p class="text-sm text-gray-600 mb-4">
      Create API keys to programmatically connect external services to your account. Keys can be used to upload files
      and access your data.
    </p>

    <!-- Create New API Key Form -->
    <div class="mb-6 pb-6 border-b border-gray-200">
      <h3 class="text-md font-medium text-gray-900 mb-3">Create New API Key</h3>
      <form @submit.prevent="handleCreateApiKey" class="space-y-4">
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Key Name (optional)</label>
          <input
              v-model="newKeyName"
              type="text"
              :disabled="createKeyLoading"
              placeholder="e.g., My Phone, Desktop App (optional)"
              class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
          />
        </div>
        <BaseButton
            type="submit"
            :disabled="createKeyLoading"
            variant="primary"
            color="blue"
            size="sm"
            title="Create New API Key"
        >
          <span v-if="createKeyLoading">Creating...</span>
          <span v-else>Create API Key</span>
        </BaseButton>
      </form>

      <!-- Display raw key after creation (shown only once) -->
      <div v-if="newKeyRawValue" class="mt-4 p-4 bg-gray-50 border border-gray-200 rounded-md shadow-sm">
        <p class="text-sm font-medium text-gray-900 mb-2">
          Please copy this key now - you'll need it to use the API. For your security, it will only be shown once.
        </p>
        <div class="flex items-center gap-2">
          <input
              ref="newKeyInput"
              :value="newKeyRawValue"
              type="text"
              readonly
              class="flex-1 px-3 py-2 bg-white border border-gray-300 rounded-md text-sm font-mono text-gray-700"
              @focus="($event.target as HTMLInputElement).select()"
          />
          <BaseButton
              @click="copyApiKey"
              variant="primary"
              color="blue"
              size="sm"
          >
            <span v-if="copyButtonShowingIcon" class="inline-flex items-center justify-center w-12 h-5">
              <ClipboardDocumentIcon class="w-4 h-4"/>
            </span>
            <span v-else class="inline-block w-12 h-5 text-center leading-5">Copy</span>
          </BaseButton>
        </div>
      </div>
    </div>

    <!-- Existing API Keys List -->
    <div>
      <h3 class="text-md font-medium text-gray-900 mb-3">Your API Keys</h3>
      <div v-if="apiKeysLoading" class="flex items-center justify-center py-12 min-h-[120px]">
        <Loader size="sm" layout="inline" message="Loading API keys..." :show-message="true" />
      </div>
      <div v-else-if="apiKeys.length === 0" class="py-8 px-4 text-center text-sm text-gray-500 bg-gray-50 border border-gray-200 rounded-md">
        No API keys created yet.
      </div>
      <div v-else class="space-y-3">
        <div
            v-for="key in apiKeys"
            :key="key.id"
            class="p-4 bg-gray-50 border border-gray-200 rounded-md hover:bg-gray-100 transition-colors"
        >
          <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-1">
                <span class="text-sm font-medium text-gray-900">{{ key.name }}</span>
              </div>
              <div class="text-xs text-gray-500 space-y-1">
                <div>Key: <span class="font-mono">{{ key.key_prefix }}...</span></div>
                <div>Created: {{ formatDate(key.created_at) }}</div>
                <div v-if="key.last_used_at">
                  Last used: {{ formatDate(key.last_used_at) }}
                </div>
                <div v-else class="text-gray-400">Never used</div>
              </div>
            </div>
            <BaseButton
                @click="handleDeleteApiKey(key.id)"
                :disabled="deleteKeyLoading === key.id"
                variant="secondary"
                color="red"
                size="sm"
                title="Delete This API Key"
            >
              <span v-if="deleteKeyLoading === key.id">Deleting...</span>
              <span v-else>Delete</span>
            </BaseButton>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
import BaseButton from '@/components/parts/BaseButton.vue';
import Loader from '@/components/parts/Loader.vue';
import { ClipboardDocumentIcon } from '@heroicons/vue/24/outline';
import { type ApiKey, createApiKey, deleteApiKey, listApiKeys } from '@/api/services/userApi';
import { toastApiError } from '@/utils/apiError';
import { formatDate } from '@/utils/dateUtils.js';

const apiKeys = ref<ApiKey[]>([]);
const apiKeysLoading = ref(false);
const newKeyName = ref('');
const newKeyRawValue = ref('');
const createKeyLoading = ref(false);
const deleteKeyLoading = ref<number | null>(null);
const copyButtonShowingIcon = ref(false);
const newKeyInput = ref<HTMLInputElement | null>(null);

async function loadApiKeys(): Promise<void> {
  apiKeysLoading.value = true;
  try {
    apiKeys.value = await listApiKeys();
  } catch (error) {
    toastApiError(error, 'Error loading API keys');
    apiKeys.value = [];
  } finally {
    apiKeysLoading.value = false;
  }
}

async function handleCreateApiKey(): Promise<void> {
  createKeyLoading.value = true;
  newKeyRawValue.value = '';

  try {
    const data = await createApiKey(newKeyName.value.trim());
    newKeyRawValue.value = data.raw_key;
    newKeyName.value = '';
    await loadApiKeys();
    await nextTick();
    newKeyInput.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  } catch (error) {
    toastApiError(error, 'An error occurred while creating the API key');
  } finally {
    createKeyLoading.value = false;
  }
}

async function handleDeleteApiKey(keyId: number): Promise<void> {
  if (!confirm('Are you sure you want to delete this API key? You can always create a new one if needed.')) {
    return;
  }

  deleteKeyLoading.value = keyId;
  try {
    await deleteApiKey(keyId);
    await loadApiKeys();
  } catch (error) {
    toastApiError(error, 'An error occurred while deleting the API key');
  } finally {
    deleteKeyLoading.value = null;
  }
}

function showCopySuccess(): void {
  copyButtonShowingIcon.value = true;
  setTimeout(() => {
    copyButtonShowingIcon.value = false;
  }, 1000);
}

async function copyApiKey(): Promise<void> {
  if (!newKeyRawValue.value) return;

  try {
    await navigator.clipboard.writeText(newKeyRawValue.value);
    showCopySuccess();
    return;
  } catch (err) {
    console.warn('Clipboard API failed, trying fallback:', err);
  }

  // Fallback for mobile and older browsers
  const input = newKeyInput.value;
  if (input) {
    try {
      input.select();
      input.setSelectionRange(0, 99999); // For mobile devices

      const successful = document.execCommand('copy');
      if (successful) {
        showCopySuccess();
      } else {
        toastApiError(new Error('Text selected - please copy manually (Ctrl+C or Cmd+C)'));
      }
    } catch (err) {
      console.error('Failed to copy:', err);
      toastApiError(err, 'Failed to copy. Please select and copy manually.');
    }
  }
}

onMounted(() => {
  void loadApiKeys();
});
</script>
