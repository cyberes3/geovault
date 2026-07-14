<template>
  <div class="flex items-center space-x-2">
    <!-- Color Preview Button (opens dialog) -->
    <button
      type="button"
      :disabled="disabled"
      :class="[
        'border border-gray-300 rounded cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed p-0.5',
        sizeClasses.visual
      ]"
      :style="{ backgroundColor: modelValue }"
      :title="modelValue"
      @click="openDialog"
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

    <!-- Color Picker Dialog -->
    <ColorPickerDialog
      :is-open="isDialogOpen"
      :model-value="modelValue"
      @update:model-value="handleColorChange"
      @close="closeDialog"
      @confirm="handleColorConfirm"
    />
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import { ArrowPathIcon } from '@heroicons/vue/24/outline'
import ColorPickerDialog from './ColorPicker.vue'

type ColorPickerElementSize = 'sm' | 'md'

export default defineComponent({
  name: 'ColorPickerElement',
  components: {
    ArrowPathIcon,
    ColorPickerDialog: ColorPickerDialog
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
      type: String as PropType<ColorPickerElementSize>,
      default: 'md', // 'sm' or 'md'
      validator: (value: string) => ['sm', 'md'].includes(value)
    }
  },
  emits: ['update:modelValue', 'reset', 'change'],
  data() {
    return {
      isDialogOpen: false
    }
  },
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
    openDialog() {
      if (!this.disabled) {
        this.isDialogOpen = true
      }
    },
    closeDialog() {
      this.isDialogOpen = false
    },
    handleColorChange() {
      // This is called during real-time updates in the dialog
      // We can emit this if needed, but typically we wait for confirm
    },
    handleColorConfirm(value: string) {
      this.$emit('update:modelValue', value)
      this.$emit('change', value)
      this.closeDialog()
    }
  }
})
</script>

