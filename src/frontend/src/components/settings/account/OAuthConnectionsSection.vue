<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-4">Authorized OAuth Applications</h2>
    <p class="text-sm text-gray-600 mb-4">
      Applications that you have signed in to with OAuth. Revoking removes their access. To create or manage OAuth
      applications (for development or third-party integrations), use the link below.
    </p>
    <p class="mb-4">
      <a
        :href="oauthApplicationsUrl"
        target="_blank"
        rel="noopener noreferrer"
        class="text-blue-600 hover:text-blue-800 underline"
      >
        Manage your OAuth applications
      </a>
    </p>

    <div v-if="oauthTokensLoading" class="flex items-center justify-center py-12 min-h-[120px]">
      <Loader size="sm" layout="inline" message="Loading authorized applications..." :showMessage="true" />
    </div>
    <div v-else-if="oauthTokens.length === 0" class="py-8 px-4 text-center text-sm text-gray-500 bg-gray-50 border border-gray-200 rounded-md">
      No authorized OAuth applications.
    </div>
    <div v-else class="space-y-3">
      <div
        v-for="token in oauthTokens"
        :key="token.id"
        class="p-4 bg-gray-50 border border-gray-200 rounded-md hover:bg-gray-100 transition-colors"
      >
        <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2 mb-1">
              <span class="text-sm font-medium text-gray-900">{{ token.application_name }}</span>
            </div>
            <div class="text-xs text-gray-500 space-y-1">
              <div>Authorized: {{ formatDate(token.created) }}</div>
              <div v-if="token.last_used_at">Last used: {{ formatDate(token.last_used_at) }}</div>
              <div v-else class="text-gray-400">Never used</div>
            </div>
          </div>
          <BaseButton
            @click="handleRevokeOAuthToken(token.id)"
            :disabled="revokeOAuthLoading === token.id"
            variant="secondary"
            color="red"
            size="sm"
            title="Revoke Access for This Application"
          >
            <span v-if="revokeOAuthLoading === token.id">Revoking...</span>
            <span v-else>Revoke</span>
          </BaseButton>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import BaseButton from '@/components/parts/BaseButton.vue';
import Loader from '@/components/parts/Loader.vue';
import { listOAuthTokens, type OAuthAuthorizedToken, revokeOAuthToken } from '@/api/services/userApi';
import { toastApiError } from '@/utils/apiError';
import { formatDate } from '@/utils/dateUtils.js';

const oauthApplicationsUrl = `${window.location.origin}/api/oauth/applications/`;

const oauthTokens = ref<OAuthAuthorizedToken[]>([]);
const oauthTokensLoading = ref(false);
const revokeOAuthLoading = ref<number | null>(null);

async function loadOAuthTokens(): Promise<void> {
  oauthTokensLoading.value = true;
  try {
    oauthTokens.value = await listOAuthTokens();
  } catch (error) {
    toastApiError(error, 'Error loading authorized applications');
    oauthTokens.value = [];
  } finally {
    oauthTokensLoading.value = false;
  }
}

async function handleRevokeOAuthToken(tokenId: number): Promise<void> {
  if (!confirm('Revoke access for this application? It will need to be authorized again to access your account.')) {
    return;
  }
  revokeOAuthLoading.value = tokenId;
  try {
    await revokeOAuthToken(tokenId);
    await loadOAuthTokens();
  } catch (error) {
    toastApiError(error, 'Failed to revoke authorization');
  } finally {
    revokeOAuthLoading.value = null;
  }
}

onMounted(() => {
  void loadOAuthTokens();
});
</script>
