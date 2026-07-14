<!--
  ToggleButton Component
  
  A reusable toggle switch component for settings across the site.
  
  Usage:
    <ToggleButton 
      v-model="isEnabled" 
      label="Enable feature"
      size="md"
      :disabled="false"
      @change="handleChange"
    />
  
  Props:
    - modelValue (Boolean): The current toggle state (required for v-model)
    - label (String): Accessible label for the toggle
    - disabled (Boolean): Whether the toggle is disabled
    - size (String): Size variant - 'sm', 'md' (default), or 'lg'
  
  Events:
    - update:modelValue: Emitted when toggle state changes (for v-model)
    - change: Emitted with new boolean value when toggled
-->
<template>
  <button
    type="button"
    :class="toggleClasses"
    :aria-label="label || (modelValue ? 'Enabled' : 'Disabled')"
    :aria-pressed="modelValue"
    @click="handleToggle"
    :disabled="disabled"
  >
    <span :class="knobClasses"></span>
  </button>
</template>

<script setup lang="ts">
import { computed, type PropType } from 'vue'

type ToggleButtonSize = 'sm' | 'md' | 'lg'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  label: {
    type: String,
    default: ''
  },
  disabled: {
    type: Boolean,
    default: false
  },
  size: {
    type: String as PropType<ToggleButtonSize>,
    default: 'md',
    validator: (value: string) => ['sm', 'md', 'lg'].includes(value)
  }
})

const emit = defineEmits(['update:modelValue', 'change'])

const handleToggle = () => {
  if (props.disabled) return
  const newValue = !props.modelValue
  emit('update:modelValue', newValue)
  emit('change', newValue)
}

// Size configurations
const sizeConfig = computed(() => {
  const configs: Record<ToggleButtonSize, { track: string; knob: string; translate: string }> = {
    sm: {
      track: 'w-9 h-5',
      knob: 'w-4 h-4',
      translate: 'translate-x-4'
    },
    md: {
      track: 'w-11 h-6',
      knob: 'w-5 h-5',
      translate: 'translate-x-5'
    },
    lg: {
      track: 'w-14 h-7',
      knob: 'w-6 h-6',
      translate: 'translate-x-7'
    }
  }
  return configs[props.size]
})

// Toggle track classes
const toggleClasses = computed(() => {
  const base = [
    'relative',
    'inline-flex',
    'items-center',
    'p-0.5',
    'rounded-full',
    'transition-colors',
    'duration-200',
    'ease-in-out',
    'focus:outline-none',
    'focus-visible:ring-2',
    'focus-visible:ring-offset-2',
    'focus-visible:ring-blue-500',
    sizeConfig.value.track
  ]
  
  if (props.disabled) {
    base.push('opacity-50', 'cursor-not-allowed')
  } else {
    base.push('cursor-pointer')
  }
  
  // Add background color based on state
  if (props.modelValue) {
    // On state - use custom blue-500 (#163D8A - main brand color)
    base.push('bg-blue-500')
  } else {
    // Off state - use custom blue-200 for a very light blue appearance (#C4D2ED)
    base.push('bg-blue-200')
  }
  
  return base.join(' ')
})

// Knob classes
const knobClasses = computed(() => {
  const base = [
    'inline-block',
    'rounded-full',
    'bg-white',
    'transform',
    'transition-transform',
    'duration-200',
    'ease-in-out',
    'shadow-sm',
    sizeConfig.value.knob
  ]
  
  if (props.modelValue) {
    base.push(sizeConfig.value.translate)
  } else {
    base.push('translate-x-0')
  }
  
  return base.join(' ')
})

</script>


