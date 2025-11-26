<template>
  <div :class="containerClasses">
    <div :class="spinnerClasses" :aria-label="message || 'Loading'">
      <div :class="borderSpinnerClasses" :style="spinnerStyle"></div>
    </div>
    
    <!-- Message -->
    <p v-if="shouldShowMessage" :class="messageClasses">{{ message }}</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  size: {
    type: String,
    default: 'md',
    validator: (value) => ['sm', 'md', 'lg'].includes(value)
  },
  layout: {
    type: String,
    default: 'centered',
    validator: (value) => ['centered', 'inline'].includes(value)
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
    type: Boolean,
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
  const sizes = {
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

// Spinner wrapper classes
const spinnerClasses = computed(() => {
  const base = 'relative'
  if (props.layout === 'inline') {
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
