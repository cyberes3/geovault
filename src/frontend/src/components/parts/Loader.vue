<template>
  <div :class="containerClasses">
    <svg
      :class="spinnerClasses"
      :style="spinnerStyle"
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      :aria-label="message || 'Loading'"
    >
      <circle :class="circleClasses" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
      <path
        :class="pathClasses"
        fill="currentColor"
        d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z"
      ></path>
    </svg>
    
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

// Spinner SVG classes
const spinnerClasses = computed(() => {
  const sizeMap = {
    sm: 'h-4 w-4',
    md: 'h-8 w-8',
    lg: 'h-12 w-12'
  }
  const base = `animate-spin ${sizeMap[props.size]}`
  if (props.layout === 'inline') {
    return `${base} -ml-1 mr-2`
  }
  return base
})

// Circle classes for opacity
const circleClasses = computed(() => {
  return 'opacity-25'
})

// Path classes for opacity
const pathClasses = computed(() => {
  return 'opacity-75'
})

// Spinner style - set color via CSS so currentColor works
const spinnerStyle = computed(() => {
  return {
    color: props.color
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
