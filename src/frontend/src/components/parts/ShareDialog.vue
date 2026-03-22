<template>
  <BaseModal
    :is-open="isOpen"
    :title="dialogTitle"
    :max-width="computedMaxWidth"
    :full-screen-mobile="shareType !== 'feature'"
    below-navbar
    @close="closeDialog"
  >
    <!-- All Share Types - Consistent Layout -->
    <div class="p-6 flex flex-col h-full">
            <!-- Display Name -->
            <div class="mb-4">
              <h4 class="text-xl font-bold text-gray-900">{{ displayName }}</h4>
            </div>

            <!-- Create New Share Section -->
            <div v-if="shareType !== 'feature'" class="mb-6 flex-shrink-0">

              <div class="space-y-4">
                <!-- Name (read-only) - Only show for collections, not tags -->
                <div v-if="shareType === 'collection'">
                  <label class="block text-sm font-medium text-gray-700 mb-1">
                    Collection Name
                  </label>
                  <input
                    :value="displayName"
                    readonly
                    class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm bg-gray-50 text-gray-500 cursor-not-allowed"
                  />
                </div>

                <!-- Include Tags Toggle (collections only) -->
                <div v-if="shareType === 'collection'" class="flex items-center gap-3">
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
                  class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                  title="Create New Share Link"
                >
                  <span v-if="creating">Creating...</span>
                  <span v-else>Create Share Link</span>
                </button>

                <!-- Error Message -->
                <div v-if="error && !shareData" class="p-3 bg-red-50 border border-red-200 rounded-md">
                  <p class="text-sm text-red-800">{{ error }}</p>
                </div>

                <!-- Success Message -->
                <div v-if="successMessage" class="p-3 bg-green-50 border border-green-200 rounded-md">
                  <p class="text-sm text-green-800">{{ successMessage }}</p>
                </div>
              </div>
            </div>

            <!-- Existing Shares Section -->
            <section class="bg-white rounded-lg shadow-sm flex-1 min-h-0 flex flex-col" :class="shareType === 'feature' && shareData ? '' : 'border border-gray-200'">
              <h4 v-if="shareType !== 'feature'" class="text-base font-semibold text-gray-900 px-4 sm:px-6 pt-4 mb-3 flex-shrink-0">Existing Share Links</h4>

              <div class="overflow-y-auto flex-1 min-h-[100px]" :class="shareType === 'feature' && shareData ? 'flex flex-col p-0' : 'px-4 sm:px-6 pb-4'">
                <div v-if="loading" class="text-center py-4">
                  <Loader size="sm" layout="centered" message="Loading shares..." />
                </div>

                <div v-else-if="shareType === 'feature' && !shareData" class="text-center py-8 text-gray-500">
                  <p class="text-sm">No share link created yet for this feature.</p>
                </div>

                <div v-else-if="shareType !== 'feature' && shares.length === 0" class="text-center py-8 text-gray-500">
                  <p class="text-sm">No share links created yet for this {{ shareType }}.</p>
                </div>

                <!-- Feature Share Display (single share) -->
                <div v-else-if="shareType === 'feature' && shareData" class="flex-1 flex flex-col">
                  <div class="border border-gray-200 rounded-lg p-4 bg-stone-50 flex-1 flex flex-col justify-between">
                    <div class="flex items-start justify-between flex-1">
                      <div class="flex-1 min-w-0 flex flex-col">
                        <!-- Share Link -->
                        <div class="mb-2">
                          <label class="block text-xs font-medium text-gray-700 mb-1">Share Link</label>
                          <div class="flex items-center space-x-2">
                            <div class="relative flex-1">
                              <input
                                :value="getFullUrl(shareData.url)"
                                readonly
                                @click="copyToClipboard(shareData.url, shareData.share_id)"
                                class="w-full px-3 py-2 text-sm border border-gray-300 rounded-md bg-gray-50 text-gray-700 font-mono overflow-hidden cursor-pointer select-none focus:outline-none focus:ring-2 focus:ring-blue-500"
                                :title="copiedShareId === shareData.share_id ? 'Copied!' : 'Click to Copy'"
                              />
                              <!-- Tooltip -->
                              <div
                                v-if="copiedShareId === shareData.share_id"
                                class="absolute -top-8 left-1/2 transform -translate-x-1/2 bg-gray-900 text-white text-xs rounded px-2 py-1 whitespace-nowrap pointer-events-none z-10"
                              >
                                Copied!
                                <div class="absolute top-full left-1/2 transform -translate-x-1/2 border-4 border-transparent border-t-gray-900"></div>
                              </div>
                            </div>
                            <button
                              @click="copyToClipboard(shareData.url, shareData.share_id)"
                              class="inline-flex items-center px-3 py-2 border border-gray-300 rounded-md text-sm font-medium text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                              :title="copiedShareId === shareData.share_id ? 'Copied!' : 'Copy Link'"
                            >
                              <ClipboardDocumentIcon v-if="copiedShareId !== shareData.share_id" class="w-4 h-4" />
                              <CheckIcon v-else class="w-4 h-4 text-green-600" />
                            </button>
                          </div>
                        </div>

                        <!-- Allow Downloads Toggle -->
                        <div class="mt-4 flex items-center gap-3">
                          <div class="flex-shrink-0">
                            <ToggleButton
                              :model-value="shareData.allow_downloads || false"
                              @update:model-value="updateAllowDownloads"
                              label="Allow Download"
                              size="md"
                              :disabled="updatingAllowDownloads"
                            />
                          </div>
                          <label class="block text-sm font-medium text-gray-700 cursor-pointer" @click="!updatingAllowDownloads && updateAllowDownloads(!shareData.allow_downloads)">
                            Allow Download
                          </label>
                          <Loader
                            v-if="updatingAllowDownloads"
                            size="sm"
                            layout="inline"
                            :showMessage="false"
                          />
                        </div>

                        <!-- Error Message -->
                        <div v-if="error && shareData" class="mt-3 p-3 bg-red-50 border border-red-200 rounded-md">
                          <p class="text-sm text-red-800">{{ error }}</p>
                        </div>

                        <!-- Share Info -->
                        <div class="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-xs text-gray-600">
                          <div class="flex items-center gap-1">
                            <span class="font-medium">Created:</span>
                            <span>{{ formatDate(shareData.created_at) }}</span>
                          </div>
                          <div v-if="shareData.access_count !== undefined" class="flex items-center gap-1">
                            <span class="font-medium">Access Count:</span>
                            <span>{{ shareData.access_count }}</span>
                          </div>
                        </div>
                      </div>

                      <!-- Delete Button -->
                      <button
                        @click="deleteShare(shareData.share_id)"
                        :disabled="deletingShareId === shareData.share_id"
                        class="ml-3 flex-shrink-0 p-2 text-red-600 hover:text-red-800 hover:bg-red-50 rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        :title="deletingShareId === shareData.share_id ? 'Deleting...' : 'Delete Share'"
                      >
                        <TrashIcon class="w-5 h-5" />
                      </button>
                    </div>
                  </div>
                </div>

                <!-- Tag/Collection Shares Display (multiple shares) -->
                <div v-else-if="shareType !== 'feature' && shares.length > 0" class="space-y-3">
                  <div
                    v-for="share in shares"
                    :key="share.share_id"
                    class="border border-gray-200 rounded-lg p-4 bg-stone-50"
                  >
                    <div class="flex items-start justify-between">
                      <div class="flex-1 min-w-0">
                        <!-- Share Link -->
                        <div class="mb-2">
                          <label class="block text-xs font-medium text-gray-700 mb-1">Share Link</label>
                          <div class="flex items-center space-x-2">
                            <div class="relative flex-1 min-w-0">
                              <input
                                :value="getFullUrl(share.url)"
                                readonly
                                @click="copyToClipboard(share.url, share.share_id)"
                                class="w-full px-3 py-2 text-sm border border-gray-300 rounded-md bg-gray-50 text-gray-700 font-mono overflow-hidden cursor-pointer select-none focus:outline-none focus:ring-2 focus:ring-blue-500"
                                :title="copiedShareId === share.share_id ? 'Copied!' : 'Click to Copy'"
                              />
                              <!-- Tooltip -->
                              <div
                                v-if="copiedShareId === share.share_id"
                                class="absolute -top-8 left-1/2 transform -translate-x-1/2 bg-gray-900 text-white text-xs rounded px-2 py-1 whitespace-nowrap pointer-events-none z-10"
                              >
                                Copied!
                                <div class="absolute top-full left-1/2 transform -translate-x-1/2 border-4 border-transparent border-t-gray-900"></div>
                              </div>
                            </div>
                            <button
                              @click="copyToClipboard(share.url, share.share_id)"
                              class="inline-flex items-center px-3 py-2 border border-gray-300 rounded-md text-sm font-medium text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                              :title="copiedShareId === share.share_id ? 'Copied!' : 'Copy Link'"
                            >
                              <ClipboardDocumentIcon v-if="copiedShareId !== share.share_id" class="w-4 h-4" />
                              <CheckIcon v-else class="w-4 h-4 text-green-600" />
                            </button>
                          </div>
                        </div>

                        <!-- Share Info -->
                        <div class="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-xs text-gray-600">
                          <div class="flex items-center gap-1">
                            <span class="font-medium">Created:</span>
                            <span>{{ formatDate(share.created_at) }}</span>
                          </div>
                          <div class="flex items-center gap-1">
                            <span class="font-medium">Access Count:</span>
                            <span>{{ share.access_count }}</span>
                          </div>
                          <div v-if="share.allow_downloads !== undefined" class="flex items-center gap-1">
                            <span class="font-medium">Download:</span>
                            <span>{{ share.allow_downloads ? 'Yes' : 'No' }}</span>
                          </div>
                          <div v-if="share.include_tags !== undefined" class="flex items-center gap-1">
                            <span class="font-medium">Include Tags:</span>
                            <span>{{ share.include_tags ? 'Yes' : 'No' }}</span>
                          </div>
                        </div>
                      </div>

                      <!-- Delete Button -->
                      <button
                        @click="deleteShare(share.share_id)"
                        :disabled="deletingShareId === share.share_id"
                        class="ml-3 flex-shrink-0 p-2 text-red-600 hover:text-red-800 hover:bg-red-50 rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        :title="deletingShareId === share.share_id ? 'Deleting...' : 'Delete Share'"
                      >
                        <TrashIcon class="w-5 h-5" />
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </section>
    </div>
  </BaseModal>
</template>

<script>
import BaseModal from './BaseModal.vue'
import Loader from './Loader.vue'
import ToggleButton from './ToggleButton.vue'
import { ClipboardDocumentIcon, CheckIcon, TrashIcon } from '@heroicons/vue/24/outline'
import { formatDate } from '@/utils/dateUtils'

function getCookie(name) {
  let cookieValue = null
  if (document.cookie && document.cookie !== '') {
    const cookies = document.cookie.split(';')
    for (let i = 0; i < cookies.length; i++) {
      const cookie = cookies[i].trim()
      if (cookie.substring(0, name.length + 1) === (name + '=')) {
        cookieValue = decodeURIComponent(cookie.substring(name.length + 1))
        break
      }
    }
  }
  return cookieValue
}

export default {
  name: 'ShareDialog',
  components: {
    BaseModal,
    Loader,
    ToggleButton,
    ClipboardDocumentIcon,
    CheckIcon,
    TrashIcon
  },
  props: {
    isOpen: {
      type: Boolean,
      required: true
    },
    shareType: {
      type: String,
      required: true,
      validator: (value) => ['tag', 'collection', 'feature'].includes(value)
    },
    // For tags: pass { tag: 'tagName' }
    // For collections: pass { id: 'uuid', name: 'collectionName' }
    // For features: pass feature object with properties.name and id
    item: {
      type: Object,
      required: true
    }
  },
  emits: ['close'],
  data() {
    return {
      shareData: null,
      shares: [],
      loading: false,
      creating: false,
      error: null,
      successMessage: null,
      copiedShareId: null,
      copied: false,
      deletingShareId: null,
      includeTags: false,
      allowDownloads: false,
      updatingAllowDownloads: false
    }
  },
  computed: {
    dialogTitle() {
      return {
        tag: 'Share Tag',
        collection: 'Share Collection',
        feature: 'Share Feature'
      }[this.shareType]
    },
    computedMaxWidth() {
      return this.shareType === 'feature' ? 'lg' : '2xl'
    },
    displayName() {
      if (this.shareType === 'tag') {
        return this.item.tag || 'Unknown Tag'
      } else if (this.shareType === 'collection') {
        return this.item.name || 'Unknown Collection'
      } else {
        // Feature name can be in properties.name or accessed via .get() for MapLibre features
        const props = this.item.properties || (this.item.get && this.item.get('properties')) || {}
        return props.name || 'Unnamed Feature'
      }
    },
    itemId() {
      if (this.shareType === 'collection') {
        return this.item.id || this.item.collection_id
      } else if (this.shareType === 'feature') {
        // Feature ID can be in different places depending on the source
        // Try properties.database_id first (most common), then id, then properties.id
        return this.item.properties?.database_id || 
               this.item.id || 
               this.item.properties?.id ||
               (this.item.get && this.item.get('properties')?.database_id)
      }
      return null
    }
  },
  watch: {
    isOpen: {
      immediate: true,
      handler(newVal) {
        if (!newVal) {
          return
        }
        if (this.shareType === 'feature') {
          this.loadOrCreateFeatureShare()
        } else {
          this.loadShares()
          this.resetForm()
        }
      }
    }
  },
  methods: {
    formatDate,
    closeDialog() {
      this.$emit('close')
      this.resetState()
    },
    resetState() {
      this.shareData = null
      this.shares = []
      this.error = null
      this.successMessage = null
      this.copiedShareId = null
      this.copied = false
      this.deletingShareId = null
    },
    resetForm() {
      this.includeTags = false
      this.allowDownloads = false
    },
    async loadOrCreateFeatureShare() {
      const featureId = this.itemId
      if (!featureId) {
        console.error('ShareDialog: Invalid feature - missing ID', {
          item: this.item,
          shareType: this.shareType,
          hasItem: !!this.item,
          hasProperties: !!this.item?.properties,
          hasGet: typeof this.item?.get === 'function'
        })
        this.error = 'Invalid feature: missing feature ID'
        return
      }

      this.loading = true
      this.error = null

      try {
        // Try to get existing share
        const response = await fetch(`/api/sharing/features/${featureId}/`, {
          method: 'GET',
          credentials: 'same-origin',
          headers: {
            'Content-Type': 'application/json'
          }
        })

        if (response.ok) {
          const data = await response.json()
          this.shareData = data
          // Set allowDownloads to match existing share
          if (data.allow_downloads !== undefined) {
            this.allowDownloads = data.allow_downloads
          }
        } else if (response.status === 404) {
          // No share exists, create one
          await this.createShare()
        } else {
          const data = await response.json()
          throw new Error(data.error || 'Failed to load share')
        }
      } catch (error) {
        console.error('Error loading share:', error)
        this.error = error.message || 'Failed to load share. Please try again.'
      } finally {
        this.loading = false
      }
    },
    async loadShares() {
      this.loading = true
      this.error = null

      try {
        const response = await fetch('/api/sharing/list/', { credentials: 'same-origin' })
        const data = await response.json()

        if (response.ok) {
          const list = Array.isArray(data.shares) ? data.shares : []
          // Filter shares based on type (normalize ids/strings so list API rows always match)
          if (this.shareType === 'tag') {
            const tag = (this.item.tag != null ? String(this.item.tag) : '').trim()
            this.shares = list.filter(
              (s) => s.share_type === 'tag' && s.tag != null && String(s.tag).trim() === tag
            )
          } else if (this.shareType === 'collection') {
            const cid = this.itemId != null ? String(this.itemId) : ''
            this.shares = list.filter(
              (s) => s.share_type === 'collection' && s.collection_id != null && String(s.collection_id) === cid
            )
          } else {
            this.shares = []
          }
        } else {
          throw new Error(data.error || 'Failed to load shares')
        }
      } catch (error) {
        console.error('Error loading shares:', error)
        this.error = error.message || 'Failed to load shares. Please try again.'
      } finally {
        this.loading = false
      }
    },
    async createShare() {
      this.creating = true
      this.error = null
      this.successMessage = null

      try {
        // Validate itemId exists for feature shares
        if (this.shareType === 'feature' && !this.itemId) {
          throw new Error('Invalid feature: missing feature ID')
        }

        const csrfToken = getCookie('csrftoken')
        const endpoint = '/api/sharing/create/'
        let body

        if (this.shareType === 'tag') {
          body = {
            share_type: 'tag',
            tag: this.item.tag,
            allow_downloads: this.allowDownloads
          }
        } else if (this.shareType === 'collection') {
          body = {
            share_type: 'collection',
            collection_id: this.itemId,
            include_tags: this.includeTags,
            allow_downloads: this.allowDownloads
          }
        } else if (this.shareType === 'feature') {
          body = {
            share_type: 'feature',
            feature_id: this.itemId,
            allow_downloads: this.allowDownloads
          }
        }

        const response = await fetch(endpoint, {
          method: 'POST',
          credentials: 'same-origin',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': csrfToken || ''
          },
          body: JSON.stringify(body)
        })

        const data = await response.json()

        if (response.ok) {
          this.successMessage = 'Share link created successfully!'
          
          if (this.shareType === 'feature') {
            this.shareData = data
            // Sync allowDownloads with the created share
            if (data.allow_downloads !== undefined) {
              this.allowDownloads = data.allow_downloads
            }
          } else {
            await this.loadShares()
          }
          
          setTimeout(() => {
            this.successMessage = null
          }, 3000)
        } else {
          throw new Error(data.error || 'Failed to create share')
        }
      } catch (error) {
        console.error('Error creating share:', error)
        this.error = error.message || 'Failed to create share. Please try again.'
      } finally {
        this.creating = false
      }
    },
    async deleteShare(shareId) {
      if (!confirm('Are you sure you want to delete this share link?')) {
        return
      }

      this.deletingShareId = shareId
      this.error = null

      try {
        const csrfToken = getCookie('csrftoken')
        const response = await fetch(`/api/sharing/${shareId}/`, {
          method: 'DELETE',
          credentials: 'same-origin',
          headers: {
            'X-CSRFToken': csrfToken || ''
          }
        })

        const data = await response.json()

        if (response.ok) {
          this.successMessage = 'Share deleted successfully!'
          
          if (this.shareType === 'feature') {
            setTimeout(() => {
              this.closeDialog()
            }, 1000)
          } else {
            await this.loadShares()
          }
        } else {
          throw new Error(data.error || 'Failed to delete share')
        }
      } catch (error) {
        console.error('Error deleting share:', error)
        this.error = error.message || 'Failed to delete share. Please try again.'
      } finally {
        this.deletingShareId = null
      }
    },
    async updateAllowDownloads(value) {
      if (this.shareType !== 'feature' || !this.shareData) {
        return
      }

      this.error = null
      this.updatingAllowDownloads = true

      try {
        const csrfToken = getCookie('csrftoken')
        const response = await fetch(`/api/sharing/features/${this.itemId}/update/`, {
          method: 'PATCH',
          credentials: 'same-origin',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': csrfToken || ''
          },
          body: JSON.stringify({
            allow_downloads: value
          })
        })

        const data = await response.json()

        if (response.ok) {
          // Update the shareData with the new value - this will automatically flip the toggle via v-model
          this.shareData.allow_downloads = data.allow_downloads
        } else {
          throw new Error(data.error || 'Failed to update download setting')
        }
      } catch (error) {
        console.error('Error updating allow downloads:', error)
        this.error = error.message || 'Failed to update download setting. Please try again.'
        // Revert the toggle if there was an error
        this.$nextTick(() => {
          if (this.shareData) {
            this.shareData.allow_downloads = !value
          }
        })
      } finally {
        this.updatingAllowDownloads = false
      }
    },
    getFullUrl(path) {
      return `${window.location.origin}${path || ''}`
    },
    async copyToClipboard(text, shareId) {
      try {
        // Construct full URL from path
        const urlToCopy = this.getFullUrl(text)
        
        await navigator.clipboard.writeText(urlToCopy)
        if (shareId) {
          this.copiedShareId = shareId
          setTimeout(() => {
            this.copiedShareId = null
          }, 2000)
        } else {
          this.copied = true
          setTimeout(() => {
            this.copied = false
          }, 2000)
        }
      } catch (error) {
        console.error('Failed to copy to clipboard:', error)
      }
    }
  }
}
</script>

