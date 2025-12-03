<template>
  <div class="flex items-center space-x-2">
    <!-- Visual Color Picker -->
    <input
      :value="modelValue"
      type="color"
      :disabled="disabled"
      :class="[
        'border border-gray-300 rounded cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed p-0.5',
        sizeClasses.visual
      ]"
      @input="handleInput"
    />
    
    <!-- Text Input -->
    <input
      :value="modelValue"
      type="text"
      :disabled="disabled"
      :placeholder="placeholder"
      :pattern="pattern"
      :class="[
        'flex-1 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed',
        sizeClasses.text
      ]"
      @input="handleInput"
    />
    
    <!-- Reset Button (optional) -->
    <button
      v-if="showReset"
      type="button"
      :disabled="disabled || !canReset"
      :class="[
        'inline-flex items-center border border-gray-300 shadow-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white',
        sizeClasses.button
      ]"
      :title="resetTitle"
      @click="$emit('reset')"
    >
      <ArrowPathIcon :class="sizeClasses.icon" />
    </button>
  </div>
</template>

<script>
import { ArrowPathIcon } from '@heroicons/vue/24/outline'

export default {
  name: 'ColorPicker',
  components: {
    ArrowPathIcon
  },
  props: {
    modelValue: {
      type: String,
      required: true
    },
    disabled: {
      type: Boolean,
      default: false
    },
    placeholder: {
      type: String,
      default: '#ff0000'
    },
    pattern: {
      type: String,
      default: '^#[0-9A-Fa-f]{6}$'
    },
    showReset: {
      type: Boolean,
      default: false
    },
    canReset: {
      type: Boolean,
      default: true
    },
    resetTitle: {
      type: String,
      default: 'Reset to original color'
    },
    size: {
      type: String,
      default: 'md', // 'sm' or 'md'
      validator: (value) => ['sm', 'md'].includes(value)
    }
  },
  emits: ['update:modelValue', 'reset', 'change'],
  computed: {
    sizeClasses() {
      if (this.size === 'sm') {
        return {
          visual: 'h-8 w-12',
          text: 'px-2 py-1.5 text-sm shadow-sm',
          button: 'px-3 py-2 text-sm leading-4',
          icon: 'w-4 h-4'
        }
      }
      // 'md' size
      return {
        visual: 'h-10 w-16',
        text: 'px-3 py-2',
        button: 'px-3 py-2 text-sm leading-4',
        icon: 'w-4 h-4'
      }
    }
  },
  methods: {
    handleInput(event) {
      const value = event.target.value
      this.$emit('update:modelValue', value)
      this.$emit('change', value)
    }
  }
}
</script>

