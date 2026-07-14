<template>
  <BaseModal
    :is-open="isOpen"
    title="Delete Tag Options"
    max-width="2xl"
    @close="closeDialog"
  >
    <div class="p-6 space-y-6">
            <!-- Tag Info -->
            <div class="mb-4">
              <div class="flex items-center space-x-3">
                <span :class="[
                  'inline-flex items-center px-3 py-1 rounded-full text-sm font-medium border',
                  isSystemTag 
                    ? 'bg-purple-100 text-purple-800 border-purple-200' 
                    : 'bg-blue-100 text-blue-700 border-blue-200'
                ]">
                  {{ tag }}
<!--                  <span v-if="isSystemTag" class="ml-1.5 text-xs opacity-75" title="System tag">🔒</span>-->
                </span>
              </div>
              <p class="mt-2 text-sm text-gray-600">
                This tag is associated with <strong>{{ featureCount }}</strong> {{ featureCount === 1 ? 'feature' : 'features' }}.
              </p>
            </div>

            <!-- Option 1: Delete All Features -->
            <section class="bg-white rounded-lg shadow-sm border-2 border-red-200 p-4 sm:p-6">
              <div class="flex items-start">
                <div class="flex-shrink-0 mt-0.5">
                  <div class="flex items-center justify-center h-8 w-8 rounded-full bg-red-100">
                    <TrashIcon class="h-5 w-5 text-red-600" />
                  </div>
                </div>
                <div class="ml-4 flex-1">
                  <h4 class="text-base font-semibold text-gray-900 mb-2">Delete All Features</h4>
                  <p class="text-sm text-gray-700 mb-4">
                    This will delete {{ featureCount }} {{ featureCount === 1 ? 'feature' : 'features' }} from your library. Deleted features cannot be recovered.
                  </p>
                  <button
                    @click="handleDeleteAllFeatures"
                    :disabled="deleting"
                    class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 disabled:opacity-50 disabled:cursor-not-allowed"
                    title="Delete All Features with This Tag"
                  >
                    <TrashIcon class="h-4 w-4 mr-2" />
                    <span v-if="deleting">Deleting...</span>
                    <span v-else>Delete All Features</span>
                  </button>
                </div>
              </div>
            </section>

            <!-- Option 2: Remove Tag Only -->
            <section :class="[
              'bg-white rounded-lg shadow-sm border-2 p-4 sm:p-6',
              isSystemTag ? 'border-gray-200 opacity-60' : 'border-blue-200'
            ]">
              <div class="flex items-start">
                <div class="flex-shrink-0 mt-0.5">
                  <div :class="[
                    'flex items-center justify-center h-8 w-8 rounded-full',
                    isSystemTag ? 'bg-gray-100' : 'bg-blue-100'
                  ]">
                    <TagIcon :class="[
                      'h-5 w-5',
                      isSystemTag ? 'text-gray-400' : 'text-blue-600'
                    ]" />
                  </div>
                </div>
                <div class="ml-4 flex-1">
                  <h4 class="text-base font-semibold text-gray-900 mb-2">Remove Tag Only</h4>
                  <p v-if="!isSystemTag" class="text-sm text-gray-700 mb-4">
                    Remove the tag "{{ tag }}" from {{ featureCount }} {{ featureCount === 1 ? 'feature' : 'features' }}. The features will remain in your library.
                  </p>
                  <p v-else class="text-sm text-gray-600 mb-4">
                    <strong>System tags cannot be removed from features.</strong> They are automatically generated based on feature properties and location data.
                  </p>
                  <button
                    @click="handleRemoveTagOnly"
                    :disabled="isSystemTag || removing"
                    class="inline-flex items-center px-4 py-2 border border-gray-300 text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-100"
                    :title="isSystemTag ? 'System tags cannot be removed from features' : 'Remove tag from all features'"
                  >
                    <TagIcon class="h-4 w-4 mr-2" />
                    <span v-if="removing">Removing...</span>
                    <span v-else>Remove Tag Only</span>
                  </button>
                </div>
              </div>
            </section>

      <!-- Error Message -->
      <div v-if="error" class="p-4 bg-red-50 border border-red-200 rounded-md">
        <div class="flex items-center">
          <ExclamationCircleIcon class="h-5 w-5 text-red-600 mr-2" />
          <p class="text-sm text-red-800">{{ error }}</p>
        </div>
      </div>
    </div>
  </BaseModal>
</template>

<script lang="ts">
import { defineComponent } from 'vue'
import BaseModal from '@/components/parts/BaseModal.vue'
import { TrashIcon, TagIcon, ExclamationCircleIcon } from '@heroicons/vue/24/outline';

export default defineComponent({
  name: 'TagDeleteModal',
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    tag: {
      type: String,
      required: true
    },
    featureCount: {
      type: Number,
      required: true
    },
    isSystemTag: {
      type: Boolean,
      default: false
    }
  },
  emits: ['close', 'delete-all-features', 'remove-tag-only'],
  components: {
    BaseModal,
    TrashIcon,
    TagIcon,
    ExclamationCircleIcon
  },
  data() {
    return {
      deleting: false,
      removing: false,
      error: null as string | null
    }
  },
  watch: {
    isOpen(newVal: boolean) {
      if (newVal) {
        this.resetState();
      }
    }
  },
  methods: {
    closeDialog() {
      if (!this.deleting && !this.removing) {
        this.$emit('close');
      }
    },
    resetState() {
      this.deleting = false;
      this.removing = false;
      this.error = null;
    },
    handleDeleteAllFeatures() {
      this.deleting = true;
      this.error = null;
      try {
        this.$emit('delete-all-features', this.tag);
      } catch (error) {
        console.error('Error deleting features:', error);
        this.error = (error as Error).message || 'Failed to delete features. Please try again.';
        this.deleting = false;
      }
      // Note: deleting flag is reset by parent when operation completes
    },
    handleRemoveTagOnly() {
      if (this.isSystemTag) {
        return;
      }
      this.removing = true;
      this.error = null;
      try {
        this.$emit('remove-tag-only', this.tag);
      } catch (error) {
        console.error('Error removing tag:', error);
        this.error = (error as Error).message || 'Failed to remove tag. Please try again.';
        this.removing = false;
      }
      // Note: removing flag is reset by parent when operation completes
    }
  },
})
</script>
