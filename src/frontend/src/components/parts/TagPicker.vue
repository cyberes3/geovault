<template>
  <div>
    <label v-if="showLabel" class="block text-sm font-medium text-gray-700 mb-1">Tags</label>

    <!-- System Tags Display (Read-only) -->
    <div v-if="systemTags.length > 0" class="mb-3">
      <div class="text-xs text-gray-500 mb-1.5">System Tags (read-only)</div>
      <div class="relative border border-gray-200 rounded-md bg-gray-50 overflow-hidden">
        <div :class="['max-h-48 overflow-y-auto p-2', hasSystemTagsOverflow ? 'pb-10' : '']" ref="systemTagsContainer">
          <div class="flex flex-wrap gap-2">
            <span
              v-for="tag in sortedSystemTags"
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
    <div class="mb-2">
      <div class="text-xs text-gray-500 mb-1.5">User Tags</div>
      <div class="relative border border-gray-200 rounded-md bg-gray-50 overflow-hidden min-h-[2.5rem]">
      <div :class="['max-h-48 overflow-y-auto p-2', hasTagsOverflow ? 'pb-10' : '']" ref="tagsContainer">
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
              title="Remove Tag"
            >
              <XMarkIcon class="w-3 h-3" />
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
    </div>

    <!-- Tag Input with Autocomplete -->
    <div class="relative" ref="tagInputContainer">
      <input
        v-model="tagInput"
        type="text"
        :disabled="disabled"
        @input="onTagInput"
        @keydown.enter.prevent="handleEnterKey"
        @keydown.arrow-down.prevent="navigateSuggestionsDown"
        @keydown.arrow-up.prevent="navigateSuggestionsUp"
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
        title="Add Tag"
      >
        <CheckIcon class="w-4 h-4" />
      </button>

      <!-- Autocomplete Suggestions -->
      <div
        v-if="showTagSuggestions && filteredTagSuggestions.length > 0"
        ref="suggestionsContainer"
        class="absolute z-50 w-full bg-white border border-gray-300 rounded-md shadow-lg max-h-48 overflow-auto"
      >
        <button
          v-for="(suggestion, index) in filteredTagSuggestions"
          :key="index"
          type="button"
          @mousedown.prevent="selectTagSuggestion(suggestion)"
          :class="[
            'w-full text-left px-3 py-2 text-sm text-gray-700 hover:bg-blue-50 focus:bg-blue-50 focus:outline-none',
            index === selectedSuggestionIndex ? 'bg-blue-100' : ''
          ]"
        >
          {{ suggestion }}
        </button>
      </div>
    </div>

    <!-- Error Message for System Tags -->
    <div v-if="systemTagError" class="mt-2 p-2 bg-red-50 border border-red-200 rounded-md">
      <p class="text-xs text-red-800">{{ systemTagError }}</p>
    </div>
  </div>
</template>

<script>
import { isSystemTag, sortTagsByPriority, sortUserTagsAlphabetically } from '@/utils/tagUtils.js'
import { XMarkIcon, CheckIcon } from '@heroicons/vue/24/outline'
import { getUserTags } from '@/api/services/featuresApi'

// Shared cache for all TagPicker instances to avoid duplicate API calls
// Cache expires after 5 seconds to ensure fresh data
const tagCache = {
  tags: null,
  timestamp: null,
  fetchPromise: null,
  CACHE_TTL: 5000 // 5 seconds
}

export default {
  name: 'TagPicker',
  components: {
    XMarkIcon,
    CheckIcon
  },
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
      hasSystemTagsOverflow: false,
      systemTagError: '',
      systemTagErrorNoSystemTagsStr: 'System tags cannot be added as user tags.',
      fetchedAvailableTags: [],
      selectedSuggestionIndex: -1
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
    effectiveAvailableTags() {
      // Prefer freshly fetched tags when available, fall back to prop otherwise
      return this.fetchedAvailableTags.length > 0 ? this.fetchedAvailableTags : this.availableTags
    },
    sortedSystemTags() {
      // Sort system tags by priority (ascending: 1 first, then 2, ..., then 0), then alphabetically
      return sortTagsByPriority(this.systemTags)
    },
    filteredTagSuggestions() {
      // Filter out system tags from suggestions
      const userTags = this.effectiveAvailableTags.filter(tag => !isSystemTag(tag))

      let suggestions;
      if (!this.tagInput.trim()) {
        suggestions = userTags.filter(tag => !this.localTags.includes(tag)).slice(0, 10)
      } else {
        const query = this.tagInput.toLowerCase().trim()
        suggestions = userTags
          .filter(tag =>
            tag.toLowerCase().includes(query) &&
            !this.localTags.includes(tag)
          )
          .slice(0, 10)
      }
      // Sort user tags alphabetically (no priority sorting for user tags)
      return sortUserTagsAlphabetically(suggestions)
    }
  },
  mounted() {
    this.fetchAvailableTags()
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
    },
    filteredTagSuggestions() {
      // Reset selected index when suggestions change
      if (this.selectedSuggestionIndex >= this.filteredTagSuggestions.length) {
        this.selectedSuggestionIndex = -1
      }
    },
    showTagSuggestions(newVal) {
      // Reset selected index when suggestions are hidden
      if (!newVal) {
        this.selectedSuggestionIndex = -1
      }
    }
  },
  methods: {
    async fetchAvailableTags() {
      // Check if cache is still valid
      const now = Date.now()
      const isCacheValid = tagCache.tags !== null &&
                          tagCache.timestamp !== null &&
                          (now - tagCache.timestamp) < tagCache.CACHE_TTL

      if (isCacheValid) {
        // Use cached tags immediately
        this.fetchedAvailableTags = tagCache.tags
        return
      }

      // If there's already a fetch in progress, wait for it
      if (tagCache.fetchPromise) {
        try {
          const tags = await tagCache.fetchPromise
          this.fetchedAvailableTags = tags
        } catch (error) {
          // If the shared fetch fails, fall back to props
          this.fetchedAvailableTags = []
        }
        return
      }

      // Start a new fetch and cache it
      tagCache.fetchPromise = (async () => {
        try {
          const data = await getUserTags()

          if (Array.isArray(data)) {
            // Sort user tags alphabetically
            const sortedTags = sortUserTagsAlphabetically(data)
            // Update cache
            tagCache.tags = sortedTags
            tagCache.timestamp = Date.now()
            tagCache.fetchPromise = null
            return sortedTags
          } else {
            // eslint-disable-next-line no-console
            console.error('Failed to fetch user tags: unexpected response shape')
            tagCache.fetchPromise = null
            return []
          }
        } catch (error) {
          // eslint-disable-next-line no-console
          console.error('Error fetching user tags:', error)
          tagCache.fetchPromise = null
          return []
        }
      })()

      // Wait for the fetch to complete
      try {
        const tags = await tagCache.fetchPromise
        this.fetchedAvailableTags = tags
      } catch (error) {
        this.fetchedAvailableTags = []
      }
    },
    onTagInput() {
      // Clear error when user starts typing
      this.systemTagError = ''
      // Reset selected index when user types
      this.selectedSuggestionIndex = -1
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
      if (this.tagInput.trim() || this.effectiveAvailableTags.length > 0) {
        this.showTagSuggestions = true
      }
    },
    hideSuggestions() {
      this.showTagSuggestions = false
      this.selectedSuggestionIndex = -1
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
    validateAndAddTag(tag, closeDropdown = true) {
      // Clear any previous error
      this.systemTagError = ''

      if (!tag) {
        return false
      }

      const lowerTag = tag.toLowerCase()

      // Check if it's a system tag
      if (isSystemTag(lowerTag)) {
        this.systemTagError = this.systemTagErrorNoSystemTagsStr
        this.tagInput = ''
        this.showTagSuggestions = false
        return false
      }

      // Add tag if not already present
      if (!this.localTags.includes(lowerTag)) {
        const newTags = [...this.localTags, lowerTag]
        this.localTags = newTags
        this.tagInput = ''
        if (closeDropdown) {
          this.showTagSuggestions = false
        }
        this.checkTagsOverflow()
        return true
      }

      return false
    },
    selectTagSuggestion(tag) {
      if (this.validateAndAddTag(tag, false)) {
        // Reset selected index
        this.selectedSuggestionIndex = -1
        // Keep dropdown open and refocus the input after a short delay to allow the blur event to complete
        setTimeout(() => {
          if (this.$refs.tagInputContainer) {
            const input = this.$refs.tagInputContainer.querySelector('input')
            if (input) {
              input.focus()
              // Ensure suggestions remain visible
              if (this.filteredTagSuggestions.length > 0) {
                this.showTagSuggestions = true
              }
            }
          }
        }, 100)
      }
    },
    handleEnterKey() {
      // If a suggestion is highlighted, select it
      if (this.selectedSuggestionIndex >= 0 && this.selectedSuggestionIndex < this.filteredTagSuggestions.length) {
        const selectedTag = this.filteredTagSuggestions[this.selectedSuggestionIndex]
        this.selectTagSuggestion(selectedTag)
      } else {
        // Otherwise, add tag from input (existing behavior)
        this.addTagFromInput()
      }
    },
    addTagFromInput() {
      const trimmedInput = this.tagInput.trim().toLowerCase()
      this.validateAndAddTag(trimmedInput)
    },
    navigateSuggestionsDown() {
      if (!this.showTagSuggestions || this.filteredTagSuggestions.length === 0) {
        return
      }
      
      if (this.selectedSuggestionIndex === -1) {
        // Start from first suggestion
        this.selectedSuggestionIndex = 0
      } else if (this.selectedSuggestionIndex < this.filteredTagSuggestions.length - 1) {
        this.selectedSuggestionIndex++
      }
      // If at last suggestion, do nothing (no wrapping)
      
      this.scrollSuggestionIntoView()
    },
    navigateSuggestionsUp() {
      if (!this.showTagSuggestions || this.filteredTagSuggestions.length === 0) {
        return
      }
      
      if (this.selectedSuggestionIndex === -1) {
        // Start from last suggestion
        this.selectedSuggestionIndex = this.filteredTagSuggestions.length - 1
      } else if (this.selectedSuggestionIndex > 0) {
        this.selectedSuggestionIndex--
      }
      // If at first suggestion, do nothing (no wrapping)
      
      this.scrollSuggestionIntoView()
    },
    scrollSuggestionIntoView() {
      this.$nextTick(() => {
        if (this.selectedSuggestionIndex >= 0 && this.$refs.suggestionsContainer) {
          const buttons = this.$refs.suggestionsContainer.querySelectorAll('button')
          if (buttons[this.selectedSuggestionIndex]) {
            buttons[this.selectedSuggestionIndex].scrollIntoView({
              behavior: 'smooth',
              block: 'nearest'
            })
          }
        }
      })
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

