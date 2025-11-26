<template>
  <div>
    <label v-if="showLabel" class="block text-sm font-medium text-gray-700 mb-1">Tags</label>

    <!-- System Tags Display (Read-only) -->
    <div v-if="systemTags.length > 0" class="mb-3">
      <div class="text-xs text-gray-500 mb-1.5">System Tags (read-only)</div>
      <div class="relative border border-gray-200 rounded-md bg-gray-50 overflow-hidden">
        <div class="max-h-20 overflow-y-auto p-2 pb-10" ref="systemTagsContainer">
          <div class="flex flex-wrap gap-2">
            <span
              v-for="tag in systemTags"
              :key="`system-${tag}`"
              class="inline-flex items-center px-2 py-1 rounded-md text-xs font-medium bg-gray-200 text-gray-600"
            >
              {{ tag }}
            </span>
          </div>
        </div>
        <!-- Gradient fade to indicate more content -->
        <div
          v-if="hasSystemTagsOverflow"
          class="absolute bottom-0 left-0 right-0 h-8 bg-gradient-to-t from-gray-50 to-transparent pointer-events-none"
        ></div>
      </div>
    </div>

    <!-- Selected Tags Display -->
    <div v-if="localTags.length > 0" class="relative mb-2 border border-gray-200 rounded-md bg-gray-50 overflow-hidden">
      <div class="max-h-24 overflow-y-auto p-2 pb-10" ref="tagsContainer">
        <div class="flex flex-wrap gap-2">
          <span
            v-for="(tag, index) in localTags"
            :key="index"
            class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-700 border border-blue-200"
          >
            {{ tag }}
            <button
              type="button"
              @click="removeTag(index)"
              :disabled="disabled"
              class="ml-1.5 inline-flex items-center justify-center w-4 h-4 rounded-full text-blue-600 hover:border-blue-200 hover:text-blue-500 focus:outline-none focus:border-blue-200 focus:text-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              title="Remove tag"
            >
              <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
              </svg>
            </button>
          </span>
        </div>
      </div>
      <!-- Gradient fade to indicate more content -->
      <div
        v-if="hasTagsOverflow"
        class="absolute bottom-0 left-0 right-0 h-8 bg-gradient-to-t from-gray-50 to-transparent pointer-events-none"
      ></div>
    </div>

    <!-- Tag Input with Autocomplete -->
    <div class="relative" ref="tagInputContainer">
      <input
        v-model="tagInput"
        type="text"
        :disabled="disabled"
        @input="onTagInput"
        @keydown.enter.prevent="addTagFromInput"
        @keydown.escape="hideSuggestions"
        @focus="showSuggestionsOnFocus"
        @blur="handleTagInputBlur"
        @keyup="convertTagInputToLowercase"
        class="tag-input w-full px-3 pr-10 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
        :placeholder="placeholder"
      />
      <!-- Checkmark Button -->
      <button
        type="button"
        @click="addTagFromInput"
        :disabled="disabled || !tagInput.trim()"
        class="absolute right-2 top-1/2 -translate-y-1/2 inline-flex items-center justify-center w-6 h-6 rounded bg-blue-600 text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-blue-600"
        title="Add tag"
      >
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"></path>
        </svg>
      </button>

      <!-- Autocomplete Suggestions -->
      <div
        v-if="showTagSuggestions && filteredTagSuggestions.length > 0"
        class="absolute z-50 w-full mt-1 bg-white border border-gray-300 rounded-md shadow-lg max-h-48 overflow-auto"
      >
        <button
          v-for="(suggestion, index) in filteredTagSuggestions"
          :key="index"
          type="button"
          @mousedown.prevent="selectTagSuggestion(suggestion)"
          class="w-full text-left px-3 py-2 text-sm text-gray-700 hover:bg-blue-50 focus:bg-blue-50 focus:outline-none"
        >
          {{ suggestion }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'TagPicker',
  props: {
    tags: {
      type: Array,
      required: true
    },
    availableTags: {
      type: Array,
      default: () => []
    },
    systemTags: {
      type: Array,
      default: () => []
    },
    disabled: {
      type: Boolean,
      default: false
    },
    placeholder: {
      type: String,
      default: 'Type to search tags...'
    },
    showLabel: {
      type: Boolean,
      default: true
    }
  },
  emits: ['update:tags'],
  data() {
    return {
      tagInput: '',
      showTagSuggestions: false,
      hasTagsOverflow: false,
      hasSystemTagsOverflow: false
    }
  },
  computed: {
    localTags: {
      get() {
        return this.tags
      },
      set(value) {
        this.$emit('update:tags', value)
      }
    },
    filteredTagSuggestions() {
      if (!this.tagInput.trim()) {
        return this.availableTags.filter(tag => !this.localTags.includes(tag)).slice(0, 10)
      }

      const query = this.tagInput.toLowerCase().trim()
      return this.availableTags
        .filter(tag =>
          tag.toLowerCase().includes(query) &&
          !this.localTags.includes(tag)
        )
        .slice(0, 10)
    }
  },
  mounted() {
    // Add scroll listener for tags container
    this.$nextTick(() => {
      if (this.$refs.tagsContainer) {
        this.$refs.tagsContainer.addEventListener('scroll', this.checkTagsOverflow)
      }
      if (this.$refs.systemTagsContainer) {
        this.$refs.systemTagsContainer.addEventListener('scroll', this.checkSystemTagsOverflow)
      }
    })
  },
  beforeUnmount() {
    // Remove scroll listener
    if (this.$refs.tagsContainer) {
      this.$refs.tagsContainer.removeEventListener('scroll', this.checkTagsOverflow)
    }
    if (this.$refs.systemTagsContainer) {
      this.$refs.systemTagsContainer.removeEventListener('scroll', this.checkSystemTagsOverflow)
    }
  },
  watch: {
    tags: {
      handler() {
        this.$nextTick(() => {
          this.checkTagsOverflow()
        })
      },
      immediate: true
    },
    systemTags: {
      handler() {
        this.$nextTick(() => {
          this.checkSystemTagsOverflow()
        })
      },
      immediate: true
    }
  },
  methods: {
    onTagInput() {
      // Convert to lowercase as user types
      this.tagInput = this.tagInput.toLowerCase()
      if (this.tagInput.trim()) {
        this.showTagSuggestions = true
      } else {
        this.showTagSuggestions = false
      }
    },
    convertTagInputToLowercase() {
      // Ensure tag input is always lowercase
      if (this.tagInput && this.tagInput !== this.tagInput.toLowerCase()) {
        this.tagInput = this.tagInput.toLowerCase()
      }
    },
    showSuggestionsOnFocus() {
      if (this.tagInput.trim() || this.availableTags.length > 0) {
        this.showTagSuggestions = true
      }
    },
    hideSuggestions() {
      this.showTagSuggestions = false
    },
    handleTagInputBlur(event) {
      // Use setTimeout to allow click events on suggestions to fire first
      setTimeout(() => {
        // Check if the related target (what we're focusing on) is not within the tag input container
        if (this.$refs.tagInputContainer && !this.$refs.tagInputContainer.contains(document.activeElement)) {
          this.showTagSuggestions = false
        }
      }, 200)
    },
    selectTagSuggestion(tag) {
      if (tag && !this.localTags.includes(tag.toLowerCase())) {
        const newTags = [...this.localTags, tag.toLowerCase()]
        this.localTags = newTags
        this.checkTagsOverflow()
      }
      this.tagInput = ''
      this.showTagSuggestions = false
      // Refocus the input after a short delay to allow the blur event to complete
      setTimeout(() => {
        if (this.$refs.tagInputContainer) {
          const input = this.$refs.tagInputContainer.querySelector('input')
          if (input) {
            input.focus()
          }
        }
      }, 100)
    },
    addTagFromInput() {
      const trimmedInput = this.tagInput.trim().toLowerCase()
      if (trimmedInput && !this.localTags.includes(trimmedInput)) {
        const newTags = [...this.localTags, trimmedInput]
        this.localTags = newTags
        this.tagInput = ''
        this.showTagSuggestions = false
        this.checkTagsOverflow()
      }
    },
    removeTag(index) {
      const newTags = [...this.localTags]
      newTags.splice(index, 1)
      this.localTags = newTags
      this.$nextTick(() => {
        this.checkTagsOverflow()
      })
    },
    checkTagsOverflow() {
      this.$nextTick(() => {
        if (this.$refs.tagsContainer) {
          const container = this.$refs.tagsContainer
          this.hasTagsOverflow = container.scrollHeight > container.clientHeight
        }
      })
    },
    checkSystemTagsOverflow() {
      this.$nextTick(() => {
        if (this.$refs.systemTagsContainer) {
          const container = this.$refs.systemTagsContainer
          this.hasSystemTagsOverflow = container.scrollHeight > container.clientHeight
        }
      })
    }
  }
}
</script>

<style scoped>
.tag-input {
  text-transform: lowercase;
}
</style>

