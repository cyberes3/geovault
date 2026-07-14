<template>
  <div
    ref="nameContainer"
    class="scroll-name-root min-w-0 overflow-hidden"
    :class="rootClass"
    @mouseenter="handleNameHover"
    @mouseleave="handleNameLeave"
    @touchstart="handleNameTouchStart"
    @touchend="handleNameTouchEnd"
  >
    <span ref="nameElement" class="scroll-name-text block truncate">
      {{ displayText }}
    </span>
    <div
      v-if="showTooltip && isOverflowing"
      class="scroll-name-tooltip"
      :style="tooltipStyle"
    >
      {{ displayText }}
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'

export default defineComponent({
  name: 'ScrollNameWithTooltip',
  props: {
    name: {
      type: String,
      default: ''
    },
    rootClass: {
      type: [String, Array, Object] as PropType<string | unknown[] | Record<string, unknown>>,
      default: ''
    }
  },
  data() {
    return {
      isOverflowing: false,
      showTooltip: false,
      tooltipStyle: {} as Record<string, string>,
      touchDismissListener: null as ((e: TouchEvent) => void) | null
    }
  },
  computed: {
    displayText(): string {
      return this.name || 'Untitled'
    }
  },
  mounted() {
    this.checkOverflow()
  },
  updated() {
    this.checkOverflow()
  },
  beforeUnmount() {
    this.removeTouchDismissListener()
  },
  methods: {
    checkOverflow() {
      void this.$nextTick(() => {
        const nameElement = this.$refs.nameElement as HTMLElement | undefined
        const nameContainer = this.$refs.nameContainer as HTMLElement | undefined
        if (nameElement && nameContainer) {
          this.isOverflowing = nameElement.scrollWidth > nameElement.clientWidth
        }
      })
    },
    handleNameHover() {
      if (this.isOverflowing) {
        this.showTooltip = true
        this.updateTooltipPosition()
      }
    },
    handleNameLeave() {
      this.showTooltip = false
    },
    handleNameTouchStart() {
      if (this.isOverflowing) {
        this.showTooltip = true
        this.updateTooltipPosition()
        void this.$nextTick(() => {
          this.addTouchDismissListener()
        })
      }
    },
    handleNameTouchEnd() {},
    addTouchDismissListener() {
      this.removeTouchDismissListener()
      this.touchDismissListener = (e: TouchEvent) => {
        const container = this.$refs.nameContainer as HTMLElement | undefined
        if (container && !container.contains(e.target as Node | null)) {
          this.showTooltip = false
          this.removeTouchDismissListener()
        }
      }
      document.addEventListener('touchstart', this.touchDismissListener, { passive: true })
    },
    removeTouchDismissListener() {
      if (this.touchDismissListener) {
        document.removeEventListener('touchstart', this.touchDismissListener)
        this.touchDismissListener = null
      }
    },
    updateTooltipPosition() {
      const container = this.$refs.nameContainer as HTMLElement | undefined
      if (container) {
        const rect = container.getBoundingClientRect()
        this.tooltipStyle = {
          left: `${rect.left + rect.width / 2}px`,
          top: `${rect.top - 8}px`,
          transform: 'translate(-50%, -100%)',
          position: 'fixed'
        }
      }
    }
  }
})
</script>

<style scoped>
.scroll-name-root {
  position: relative;
}

.scroll-name-text {
  min-width: 0;
}

.scroll-name-tooltip {
  position: fixed;
  z-index: 9999;
  background-color: rgba(0, 0, 0, 0.9);
  color: white;
  padding: 0.375rem 0.5rem;
  border-radius: 0.25rem;
  font-size: 0.8125rem;
  font-weight: 500;
  line-height: 1.2;
  white-space: normal;
  word-wrap: break-word;
  max-width: 300px;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
  pointer-events: none;
}

.scroll-name-tooltip::after {
  content: '';
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  border: 5px solid transparent;
  border-top-color: rgba(0, 0, 0, 0.9);
}

@media (max-width: 768px) {
  .scroll-name-tooltip {
    max-width: calc(100vw - 2rem);
  }
}
</style>
