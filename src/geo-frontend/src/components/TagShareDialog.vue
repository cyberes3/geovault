<template>
  <!-- Modal Backdrop -->
  <div v-if="isOpen" class="fixed inset-0 z-50 overflow-y-auto" @mousedown="handleBackdropMouseDown">
    <div class="flex items-center justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
      <!-- Background overlay -->
      <div class="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity"></div>

      <!-- Modal panel -->
      <div class="inline-block align-bottom bg-white rounded-lg text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-2xl sm:w-full" @click.stop @mousedown.stop>
        <!-- Header -->
        <div class="bg-white px-6 py-4 border-b border-gray-200">
          <div class="flex items-center justify-between">
            <h3 class="text-lg font-medium text-gray-900">Share Tag: {{ tag }}</h3>
            <button
              @click="closeDialog"
              class="text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150"
            >
              <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        <!-- Content -->
        <div class="bg-white p-6 max-h-[80vh] overflow-y-auto">
          <!-- Create New Share Section -->
          <div class="mb-6">
            <h4 class="text-sm font-semibold text-gray-900 mb-4">Create New Share Link</h4>
            
            <div class="space-y-4">
              <!-- Tag Name (read-only) -->
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">Tag Name</label>
                <input
                  :value="tag"
                  readonly
                  class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm bg-gray-50 text-gray-500 cursor-not-allowed"
                />
              </div>

              <!-- Create Button -->
              <button
                @click="createShare"
                :disabled="creating"
                class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <span v-if="creating">Creating...</span>
                <span v-else>Create Share Link</span>
              </button>

              <!-- Error Message -->
              <div v-if="error" class="p-3 bg-red-50 border border-red-200 rounded-md">
                <p class="text-sm text-red-800">{{ error }}</p>
              </div>

              <!-- Success Message -->
              <div v-if="successMessage" class="p-3 bg-green-50 border border-green-200 rounded-md">
                <p class="text-sm text-green-800">{{ successMessage }}</p>
              </div>
            </div>
          </div>

          <!-- Existing Shares Section -->
          <div>
            <h4 class="text-sm font-semibold text-gray-900 mb-4">Existing Share Links</h4>
            
            <div v-if="loading" class="text-center py-4">
              <div class="animate-spin rounded-full h-6 w-6 border-b-2 border-blue-600 mx-auto"></div>
              <p class="mt-2 text-sm text-gray-600">Loading shares...</p>
            </div>

            <div v-else-if="tagShares.length === 0" class="text-center py-8 text-gray-500">
              <p class="text-sm">No share links created yet for this tag.</p>
            </div>

            <div v-else class="space-y-3">
              <div
                v-for="share in tagShares"
                :key="share.share_id"
                class="border border-gray-200 rounded-lg p-4 hover:bg-gray-50 transition-colors"
              >
                <div class="flex items-start justify-between">
                  <div class="flex-1 min-w-0">
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
                    @click="deleteShare(share.share_id)"
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
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { getCookie } from "@/assets/js/auth.js";

export default {
  name: 'TagShareDialog',
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    tag: {
      type: String,
      required: true
    }
  },
  emits: ['close'],
  data() {
    return {
      creating: false,
      loading: false,
      error: null,
      successMessage: null,
      tagShares: [],
      copiedShareId: null,
      deletingShareId: null
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        this.loadShares();
        this.resetForm();
        // Add escape key listener when dialog opens
        document.addEventListener('keydown', this.handleEscapeKey);
      } else {
        // Remove escape key listener when dialog closes
        document.removeEventListener('keydown', this.handleEscapeKey);
      }
    }
  },
  methods: {
    handleBackdropMouseDown(event) {
      if (event.target === event.currentTarget) {
        this.closeDialog();
      }
    },
    handleEscapeKey(event) {
      if (event.key === 'Escape' && this.isOpen) {
        this.closeDialog();
      }
    },
    closeDialog() {
      this.$emit('close');
    },
    resetForm() {
      this.error = null;
      this.successMessage = null;
      this.copiedShareId = null;
    },
    async loadShares() {
      this.loading = true;
      this.error = null;

      try {
        const csrfToken = getCookie('csrftoken');
        const response = await fetch('/api/data/sharing/list/', {
          headers: {
            'X-CSRFToken': csrfToken || ''
          }
        });

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();

        if (data.success && data.shares) {
          // Filter shares for this tag
          this.tagShares = data.shares.filter(share => share.tag === this.tag);
        } else {
          throw new Error(data.error || 'Failed to load shares');
        }
      } catch (error) {
        console.error('Error loading shares:', error);
        this.error = error.message || 'Failed to load shares. Please try again.';
      } finally {
        this.loading = false;
      }
    },
    async createShare() {
      this.creating = true;
      this.error = null;
      this.successMessage = null;

      try {
        const csrfToken = getCookie('csrftoken');
        const response = await fetch('/api/data/sharing/create/', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': csrfToken || ''
          },
          body: JSON.stringify({
            tag: this.tag
          })
        });

        const data = await response.json();

        if (data.success) {
          this.successMessage = 'Share link created successfully!';
          // Reload shares to show the new one
          await this.loadShares();
          // Reset form
        } else {
          throw new Error(data.error || 'Failed to create share');
        }
      } catch (error) {
        console.error('Error creating share:', error);
        this.error = error.message || 'Failed to create share. Please try again.';
      } finally {
        this.creating = false;
      }
    },
    async deleteShare(shareId) {
      if (!confirm('Are you sure you want to delete this share link?')) {
        return;
      }

      this.deletingShareId = shareId;
      this.error = null;

      try {
        const csrfToken = getCookie('csrftoken');
        const response = await fetch(`/api/data/sharing/${shareId}/`, {
          method: 'DELETE',
          headers: {
            'X-CSRFToken': csrfToken || ''
          }
        });

        const data = await response.json();

        if (data.success) {
          // Reload shares
          await this.loadShares();
        } else {
          throw new Error(data.error || 'Failed to delete share');
        }
      } catch (error) {
        console.error('Error deleting share:', error);
        this.error = error.message || 'Failed to delete share. Please try again.';
      } finally {
        this.deletingShareId = null;
      }
    },
    async copyToClipboard(text) {
      try {
        await navigator.clipboard.writeText(text);
        this.copiedShareId = text;
        setTimeout(() => {
          this.copiedShareId = null;
        }, 2000);
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
          this.copiedShareId = text;
          setTimeout(() => {
            this.copiedShareId = null;
          }, 2000);
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
  beforeUnmount() {
    // Clean up event listener when component is destroyed
    document.removeEventListener('keydown', this.handleEscapeKey);
  }
}
</script>

