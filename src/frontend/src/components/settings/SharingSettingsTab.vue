<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-4">Shared Links</h2>

    <div v-if="sharesLoading" class="text-center py-8">
      <div class="animate-spin rounded-full h-8 w-8 border-2 border-transparent mx-auto" style="border-bottom-color: #4B6BAB;"></div>
      <p class="mt-2 text-sm text-gray-600">Loading shares...</p>
    </div>

    <div v-else-if="sharesError" class="p-4 bg-red-50 border border-red-200 rounded-md">
      <p class="text-sm text-red-800">{{ sharesError }}</p>
      <button
        @click="loadShares"
        class="mt-2 text-sm text-red-600 hover:text-red-800 underline"
        title="Retry loading shares"
      >
        Try again
      </button>
    </div>

    <div v-else-if="shares.length === 0" class="text-center py-8 text-gray-500">
      <svg class="mx-auto h-12 w-12 text-gray-400 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8.684 13.342C8.885 12.938 9 12.482 9 12c0-.482-.115-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"></path>
      </svg>
      <p class="text-sm">No share links created yet.</p>
      <p class="text-xs mt-1">Create share links from the Tags or Collections page.</p>
    </div>

    <div v-else class="space-y-3">
      <div
        v-for="share in shares"
        :key="share.share_id"
        class="border border-gray-200 rounded-lg p-4 hover:bg-gray-50 transition-colors"
      >
        <div class="flex items-start justify-between">
          <div class="flex-1 min-w-0">
            <!-- Share Type and Name -->
            <div class="mb-2 flex items-center gap-2">
              <!-- Share Type Badge -->
              <span
                v-if="share.share_type === 'tag'"
                class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-purple-100 text-purple-800"
                title="Tag Share"
              >
                <svg class="w-3 h-3 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z"></path>
                </svg>
                Tag
              </span>
              <span
                v-else-if="share.share_type === 'collection'"
                class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800"
                title="Collection Share"
              >
                <svg class="w-3 h-3 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"></path>
                </svg>
                Collection
              </span>
              <!-- Tag or Collection Name -->
              <span
                v-if="share.share_type === 'tag'"
                class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800"
              >
                {{ share.tag }}
              </span>
              <span
                v-else-if="share.share_type === 'collection'"
                class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800"
              >
                {{ share.collection_name }}
              </span>
            </div>

            <!-- Share Link -->
            <div class="mb-2">
              <label class="block text-xs font-medium text-gray-700 mb-1">Share Link</label>
              <div class="flex items-center space-x-2">
                <input
                  :value="share.url"
                  readonly
                  class="flex-1 px-3 py-2 text-sm border border-gray-300 rounded-md bg-gray-50 text-gray-700 font-mono"
                />
                <button
                  @click="copyToClipboard(share.url)"
                  class="inline-flex items-center px-3 py-2 border border-gray-300 rounded-md text-sm font-medium text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                  :title="copiedShareId === share.share_id ? 'Copied!' : 'Copy link'"
                >
                  <svg v-if="copiedShareId !== share.share_id" class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"></path>
                  </svg>
                  <svg v-else class="w-4 h-4 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"></path>
                  </svg>
                </button>
              </div>
            </div>

            <!-- Share Info -->
            <div class="grid grid-cols-2 gap-4 text-xs text-gray-600">
              <div>
                <span class="font-medium">Created:</span>
                <span class="ml-1">{{ formatDate(share.created_at) }}</span>
              </div>
              <div>
                <span class="font-medium">Access Count:</span>
                <span class="ml-1">{{ share.access_count }}</span>
              </div>
            </div>
          </div>

          <!-- Delete Button -->
          <button
            @click="deleteShare(share.share_id, share.share_type)"
            :disabled="deletingShareId === share.share_id"
            class="ml-4 p-2 text-red-600 hover:text-red-800 hover:bg-red-50 rounded-md focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 disabled:opacity-50"
            title="Delete share"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
            </svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import axios from "axios";
import { getCookie } from "@/assets/js/auth.js";

export default {
  name: 'SharingSettingsTab',
  props: {
    toastRef: {
      type: Object,
      default: null
    }
  },
  data() {
    return {
      // Sharing tab data
      dataLoaded: false,
      shares: [],
      sharesLoading: false,
      sharesError: null,
      copiedShareId: null,
      deletingShareId: null
    }
  },
  methods: {
    async loadShares() {
      this.sharesLoading = true;
      this.sharesError = null;

      try {
        const response = await axios.get('/api/data/sharing/list/', {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        });

        if (response.status === 200 && response.data.shares) {
          this.shares = response.data.shares;
        } else {
          throw new Error(response.data.error || 'Failed to load shares');
        }
      } catch (error) {
        console.error('Error loading shares:', error);
        this.sharesError = error.response?.data?.error || error.message || 'Failed to load shares. Please try again.';
      } finally {
        this.sharesLoading = false;
      }
    },
    async deleteShare(shareId, shareType) {
      if (!confirm('Are you sure you want to delete this share link?')) {
        return;
      }

      this.deletingShareId = shareId;
      this.sharesError = null;

      try {
        // Single endpoint handles both tag and collection shares
        const response = await axios.delete(`/api/data/sharing/${shareId}/`, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        });

        if (response.status === 200) {
          // Reload shares
          await this.loadShares();
        } else {
          throw new Error(response.data.error || 'Failed to delete share');
        }
      } catch (error) {
        console.error('Error deleting share:', error);
        this.sharesError = error.response?.data?.error || error.message || 'Failed to delete share. Please try again.';
        if (this.toastRef) {
          this.toastRef.error(this.sharesError);
        }
      } finally {
        this.deletingShareId = null;
      }
    },
    async copyToClipboard(text) {
      try {
        await navigator.clipboard.writeText(text);
        // Find the share by URL to set copiedShareId
        const share = this.shares.find(s => s.url === text);
        if (share) {
          this.copiedShareId = share.share_id;
          setTimeout(() => {
            this.copiedShareId = null;
          }, 2000);
        }
      } catch (error) {
        console.error('Error copying to clipboard:', error);
        // Fallback for older browsers
        const textArea = document.createElement('textarea');
        textArea.value = text;
        textArea.style.position = 'fixed';
        textArea.style.opacity = '0';
        document.body.appendChild(textArea);
        textArea.select();
        try {
          document.execCommand('copy');
          const share = this.shares.find(s => s.url === text);
          if (share) {
            this.copiedShareId = share.share_id;
            setTimeout(() => {
              this.copiedShareId = null;
            }, 2000);
          }
        } catch (err) {
          console.error('Fallback copy failed:', err);
        }
        document.body.removeChild(textArea);
      }
    },
    formatDate(dateString) {
      const date = new Date(dateString);
      return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
  },
  async created() {
    if (!this.dataLoaded) {
      await this.loadShares();
      this.dataLoaded = true;
    }
  }
}
</script>

