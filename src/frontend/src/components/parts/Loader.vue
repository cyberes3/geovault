<template>
  <div :class="containerClasses">
    <div :class="spinnerClasses" :aria-label="message || 'Loading'">
      <div :class="borderSpinnerClasses" :style="spinnerStyle"></div>
    </div>
    
    <!-- Message -->
    <p v-if="shouldShowMessage" :class="messageClasses">{{ message }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, type PropType } from 'vue'

type LoaderSize = 'xs' | 'sm' | 'md' | 'lg'
type LoaderLayout = 'centered' | 'inline'

const props = defineProps({
  size: {
    type: String as PropType<LoaderSize>,
    default: 'md',
    validator: (value: string) => ['xs', 'sm', 'md', 'lg'].includes(value)
  },
  layout: {
    type: String as PropType<LoaderLayout>,
    default: 'centered',
    validator: (value: string) => ['centered', 'inline'].includes(value)
  },
  message: {
    type: String,
    default: 'Loading...'
  },
  color: {
    type: String,
    default: '#4B6BAB'
  },
  showMessage: {
    type: Boolean as PropType<boolean | null>,
    default: null // Will be computed based on layout if not provided
  },
  bold: {
    type: Boolean,
    default: false
  }
})

// Computed properties
const shouldShowMessage = computed(() => {
  if (props.showMessage !== null) {
    return props.showMessage
  }
  return props.layout === 'centered'
})

// Size classes
const sizeClasses = computed(() => {
  const sizes: Record<LoaderSize, { border: string; message: string }> = {
    xs: {
      border: 'w-5 h-5 border-2',
      message: 'text-xs'
    },
    sm: {
      border: 'w-4 h-4 border-2',
      message: 'text-xs'
    },
    md: {
      border: 'w-8 h-8 border-2',
      message: 'text-sm'
    },
    lg: {
      border: 'w-12 h-12 border-2',
      message: 'text-base'
    }
  }
  return sizes[props.size]
})

// Container classes
const containerClasses = computed(() => {
  if (props.layout === 'inline') {
    return 'inline-flex items-center'
  }
  return 'flex flex-col items-center justify-center py-12'
})

// Spinner wrapper classes (omit -ml-1 mr-2 when no message so spinner stays centered in icon slots)
const spinnerClasses = computed(() => {
  const base = 'relative'
  if (props.layout === 'inline' && props.showMessage !== false) {
    return `${base} -ml-1 mr-2`
  }
  return base
})

// Border spinner classes
const borderSpinnerClasses = computed(() => {
  return `${sizeClasses.value.border} border-transparent rounded-full animate-spin`
})

// Border spinner style
const spinnerStyle = computed(() => {
  return {
    'border-bottom-color': props.color
  }
})

// Message classes
const messageClasses = computed(() => {
  const base = sizeClasses.value.message
  const boldClass = props.bold ? 'font-bold' : ''
  if (props.layout === 'inline') {
    return `${base} ml-2 text-gray-600 ${boldClass}`.trim()
  }
  return `${base} mt-4 text-gray-600 ${boldClass}`.trim()
})
</script>

<style scoped>

</style>
