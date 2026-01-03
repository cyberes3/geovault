<template>
  <div
    v-if="isOpen"
    class="fixed inset-0 z-50"
    role="dialog"
    aria-modal="true"
    @mousedown="handleBackdropMouseDown"
  >
    <!-- Backdrop -->
    <div class="absolute inset-0 bg-black/50"></div>

    <!-- Modal panel -->
    <div class="absolute inset-0 flex items-stretch justify-stretch sm:items-center sm:justify-center">
      <div
        class="bg-white flex flex-col w-full h-full sm:h-auto sm:max-w-2xl sm:rounded-lg shadow-xl overflow-hidden"
        @mousedown.stop
        @click.stop
      >
        <!-- Header (sticky) -->
        <header class="sticky top-0 z-10 flex items-center justify-between px-6 py-4 border-b border-gray-200 bg-gray-50 sm:rounded-t-lg">
          <h3 class="text-lg sm:text-xl font-semibold text-gray-900">
            Delete Tag Options
          </h3>
          <button
            @click="closeDialog"
            class="p-2 sm:p-1 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150"
            title="Close dialog"
          >
            <XMarkIcon class="h-6 w-6" />
          </button>
        </header>

        <!-- Content -->
        <main class="flex-1 overflow-y-auto bg-white min-h-0">
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
                    title="Delete all features with this tag"
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
        </main>
      </div>
    </div>
  </div>
</template>

<script>
import { XMarkIcon, TrashIcon, TagIcon, ExclamationCircleIcon } from '@heroicons/vue/24/outline';

export default {
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
    XMarkIcon,
    TrashIcon,
    TagIcon,
    ExclamationCircleIcon
  },
  data() {
    return {
      deleting: false,
      removing: false,
      error: null
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        document.body.classList.add('overflow-hidden');
        this.resetState();
        // Add escape key listener when dialog opens
        document.addEventListener('keydown', this.handleEscapeKey);
        // Move modal to body to avoid parent container offsets
        this.$nextTick(() => {
          if (this.$el && this.$el.parentNode !== document.body) {
            document.body.appendChild(this.$el);
          }
        });
      } else {
        document.body.classList.remove('overflow-hidden');
        // Remove escape key listener when dialog closes
        document.removeEventListener('keydown', this.handleEscapeKey);
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
      if (event.key === 'Escape' && this.isOpen) {
        this.closeDialog();
      }
    },
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
    async handleDeleteAllFeatures() {
      this.deleting = true;
      this.error = null;
      try {
        await this.$emit('delete-all-features', this.tag);
      } catch (error) {
        console.error('Error deleting features:', error);
        this.error = error.message || 'Failed to delete features. Please try again.';
        this.deleting = false;
      }
      // Note: deleting flag is reset by parent when operation completes
    },
    async handleRemoveTagOnly() {
      if (this.isSystemTag) {
        return;
      }
      this.removing = true;
      this.error = null;
      try {
        await this.$emit('remove-tag-only', this.tag);
      } catch (error) {
        console.error('Error removing tag:', error);
        this.error = error.message || 'Failed to remove tag. Please try again.';
        this.removing = false;
      }
      // Note: removing flag is reset by parent when operation completes
    }
  },
  beforeUnmount() {
    // Clean up event listener when component is destroyed
    document.removeEventListener('keydown', this.handleEscapeKey);
    document.body.classList.remove('overflow-hidden');
  }
}
</script>
