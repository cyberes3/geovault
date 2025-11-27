<template>
  <svg
    :class="['w-4 h-4 flex-shrink-0 text-gray-600 transition-transform duration-200', rotationClass]"
    fill="none"
    viewBox="0 0 24 24"
    stroke-width="2"
    stroke="currentColor"
  >
    <!-- Top horizontal bar -->
    <line x1="8" y1="2" x2="16" y2="2" stroke-linecap="round" />
    <!-- Upward arrow -->
    <path stroke-linecap="round" stroke-linejoin="round" d="M12 10V4m-3 3 3-3 3 3" />
    <!-- Center vertical line -->
    <line x1="12" y1="10" x2="12" y2="14" stroke-linecap="round" />
    <!-- Downward arrow -->
    <path stroke-linecap="round" stroke-linejoin="round" d="M12 14v6m-3-3 3 3 3-3" />
    <!-- Bottom horizontal bar -->
    <line x1="8" y1="22" x2="16" y2="22" stroke-linecap="round" />
  </svg>
</template>

<script>
export default {
  name: 'MeasurementIcon',
  props: {
    rotation: {
      type: [Number, String],
      default: 0,
      validator: (value) => !isNaN(Number(value))
    }
  },
  computed: {
    rotationClass() {
      const deg = Number(this.rotation)
      if (deg === 90) return 'rotate-90'
      if (deg === 180) return 'rotate-180'
      if (deg === 270 || deg === -90) return '-rotate-90'
      if (deg !== 0) return `rotate-[${deg}deg]` // Tailwind arbitrary value support might vary, but class binding works
      return ''
    }
  }
}
</script>

