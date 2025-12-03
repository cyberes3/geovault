<template>
  <div
    v-if="isOpen"
    ref="dialogBackdrop"
    class="fixed inset-0 z-50"
    role="dialog"
    aria-modal="true"
    @mousedown="handleBackdropMouseDown"
    tabindex="-1"
  >
    <!-- Background overlay -->
    <div class="absolute inset-0 bg-black/50 transition-opacity"></div>

    <!-- Modal panel -->
    <div class="absolute inset-0 flex items-stretch justify-stretch sm:items-center sm:justify-center">
      <div
        class="bg-white flex flex-col w-full h-full sm:h-[80vh] sm:max-w-2xl sm:rounded-lg shadow-xl overflow-hidden"
        @click.stop
        @mousedown.stop
      >
        <!-- Header -->
        <div class="flex items-center justify-between px-6 py-4 border-b border-gray-200 bg-gray-50 sm:rounded-t-lg">
          <h3 class="text-lg font-medium text-gray-900">Share Collection</h3>
          <button
            @click="closeDialog"
            class="text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150"
            title="Close dialog"
          >
            <XMarkIcon class="h-6 w-6" />
          </button>
        </div>

        <!-- Content -->
        <div class="flex-1 overflow-y-auto bg-white p-6 flex flex-col">
          <!-- Collection Name Display -->
          <div class="mb-4">
            <h4 class="text-xl font-bold text-gray-900">{{ collectionName }}</h4>
          </div>
          <!-- Create New Share Section -->
          <div class="mb-6 flex-shrink-0">
            <h4 class="text-sm font-semibold text-gray-900 mb-4">Create New Share Link</h4>

            <div class="space-y-4">
              <!-- Collection Name (read-only) -->
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">Collection Name</label>
                <input
                  :value="collectionName"
                  readonly
                  class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm bg-gray-50 text-gray-500 cursor-not-allowed"
                />
              </div>

              <!-- Include Tags Toggle -->
              <div class="flex items-center gap-3">
                <div class="flex-shrink-0">
                  <ToggleButton
                    v-model="includeTags"
                    label="Include Tags"
                    size="md"
                  />
                </div>
                <label class="block text-sm font-medium text-gray-700 cursor-pointer" @click="includeTags = !includeTags">
                  Include Tags
                </label>
              </div>

              <!-- Allow Downloads Toggle -->
              <div class="flex items-center gap-3">
                <div class="flex-shrink-0">
                  <ToggleButton
                    v-model="allowDownloads"
                    label="Allow Download"
                    size="md"
                  />
                </div>
                <label class="block text-sm font-medium text-gray-700 cursor-pointer" @click="allowDownloads = !allowDownloads">
                  Allow Download
                </label>
              </div>

              <!-- Create Button -->
              <button
                @click="createShare"
                :disabled="creating"
                class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                title="Create new share link"
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
          <div class="flex-1 min-h-0 flex flex-col">
            <h4 class="text-sm font-semibold text-gray-900 mb-4 flex-shrink-0">Existing Share Links</h4>

            <div class="overflow-y-auto flex-1 min-h-[100px] -mr-2 pr-2">
              <div v-if="loading" class="text-center py-4">
                <Loader size="sm" layout="centered" message="Loading shares..." />
              </div>

              <div v-else-if="collectionShares.length === 0" class="text-center py-8 text-gray-500">
                <p class="text-sm">No share links created yet for this collection.</p>
              </div>

              <div v-else class="space-y-3">
                <div
                  v-for="share in collectionShares"
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
                          <ClipboardDocumentIcon v-if="copiedShareId !== share.share_id" class="w-4 h-4" />
                          <CheckIcon v-else class="w-4 h-4 text-green-600" />
                        </button>
                      </div>
                    </div>

                    <!-- Share Info -->
                    <div class="flex flex-wrap gap-x-4 gap-y-2 text-xs text-gray-600">
                      <div class="flex-shrink-0">
                        <span class="font-medium">Created:</span>
                        <span class="ml-1 whitespace-nowrap">{{ formatDate(share.created_at) }}</span>
                      </div>
                      <div class="flex-shrink-0">
                        <span class="font-medium">Access Count:</span>
                        <span class="ml-1">{{ share.access_count }}</span>
                      </div>
                      <div v-if="share.include_tags !== undefined" class="flex-shrink-0">
                        <span class="font-medium">Tags:</span>
                        <span class="ml-1">{{ share.include_tags ? 'Yes' : 'No' }}</span>
                      </div>
                      <div v-if="share.allow_downloads !== undefined" class="flex-shrink-0">
                        <span class="font-medium">Download:</span>
                        <span class="ml-1">{{ share.allow_downloads ? 'Yes' : 'No' }}</span>
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
                    <TrashIcon class="w-5 h-5" />
                  </button>
                </div>
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
import Loader from "@/components/parts/Loader.vue";
import ToggleButton from "@/components/parts/ToggleButton.vue";
import { XMarkIcon, ClipboardDocumentIcon, CheckIcon, TrashIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'CollectionShareDialog',
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    collection: {
      type: Object,
      required: true,
      validator: (value) => value && (value.id || value.collection_id) && value.name
    }
  },
  emits: ['close'],
  components: {
    Loader,
    ToggleButton,
    XMarkIcon,
    ClipboardDocumentIcon,
    CheckIcon,
    TrashIcon
  },
  computed: {
    collectionId() {
      return this.collection.id || this.collection.collection_id;
    },
    collectionName() {
      return this.collection.name || 'Unknown Collection';
    }
  },
  data() {
    return {
      creating: false,
      loading: false,
      error: null,
      successMessage: null,
      collectionShares: [],
      copiedShareId: null,
      deletingShareId: null,
      includeTags: false,
      allowDownloads: false
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        document.body.classList.add('overflow-hidden');
        this.loadShares();
        this.resetForm();
        // Focus the dialog so it can receive keyboard events and move to body to avoid layout offsets
        this.$nextTick(() => {
          if (this.$refs.dialogBackdrop) {
            this.$refs.dialogBackdrop.focus();
          }
          if (this.$el && this.$el.parentNode !== document.body) {
            document.body.appendChild(this.$el);
          }
        });
      } else {
        document.body.classList.remove('overflow-hidden');
      }
    },
    $route() {
      // Close dialog when route changes
      if (this.isOpen) {
        this.closeDialog();
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
      if ((event.key === 'Escape' || event.keyCode === 27) && this.isOpen) {
        event.preventDefault();
        event.stopPropagation();
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
      this.includeTags = false;
      this.allowDownloads = false;
    },
    async loadShares() {
      this.loading = true;
      this.error = null;

      try {
        const csrfToken = getCookie('csrftoken');
        const response = await fetch('/api/sharing/list/', {
          headers: {
            'X-CSRFToken': csrfToken || ''
          }
        });

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();

        if (response.ok && data.shares) {
          // Filter shares for this collection
          const collectionIdStr = String(this.collectionId);
          this.collectionShares = data.shares.filter(
            share => share.share_type === 'collection' && share.collection_id === collectionIdStr
          );
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
        const response = await fetch('/api/sharing/collections/create/', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': csrfToken || ''
          },
          body: JSON.stringify({
            collection_id: this.collectionId,
            include_tags: this.includeTags,
            allow_downloads: this.allowDownloads
          })
        });

        const data = await response.json();

        if (response.ok) {
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
        const response = await fetch(`/api/sharing/${shareId}/`, {
          method: 'DELETE',
          headers: {
            'X-CSRFToken': csrfToken || ''
          }
        });

        const data = await response.json();

        if (response.ok) {
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
        // Find the share by URL to set copiedShareId
        const share = this.collectionShares.find(s => s.url === text);
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
          const share = this.collectionShares.find(s => s.url === text);
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
  mounted() {
    // Add escape key listener on mount - it will check isOpen before acting
    document.addEventListener('keydown', this.handleEscapeKey);
    // If dialog is already open when component mounts (v-if case), load shares
    if (this.isOpen) {
      document.body.classList.add('overflow-hidden');
      this.loadShares();
      this.resetForm();
      this.$nextTick(() => {
        if (this.$el && this.$el.parentNode !== document.body) {
          document.body.appendChild(this.$el);
        }
      });
    }
  },
  beforeUnmount() {
    // Clean up event listener when component is destroyed
    document.removeEventListener('keydown', this.handleEscapeKey);
    document.body.classList.remove('overflow-hidden');
  }
}
</script>

