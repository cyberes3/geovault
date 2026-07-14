<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    xmlns="http://www.w3.org/2000/svg"
    class="block flex-shrink-0"
    :class="$attrs.class"
    :style="{ minWidth: size + 'px', maxWidth: size + 'px', minHeight: size + 'px', maxHeight: size + 'px' }"
    role="img"
    aria-hidden="true"
  >
    <g transform="translate(16,2.6) scale(0.8) translate(-16,-2.6)">
      <path
        :fill="fill"
        :stroke="stroke"
        :stroke-width="strokeWidth"
        stroke-linejoin="round"
        shape-rendering="geometricPrecision"
        :d="ARROW_PATH_D"
      />
    </g>
  </svg>
</template>

<script lang="ts">
import { defineComponent } from 'vue';
import { ARROW_PATH_D } from './trackArrowMap';

export default defineComponent({
  name: 'TrackListChevronIcon',
  inheritAttrs: false,
  props: {
    /** Icon size in pixels. */
    size: { type: Number, default: 20 },
    /** Fill color (default currentColor to inherit from parent, e.g. text-gray-500). */
    fill: { type: String, default: 'currentColor' },
    /** Stroke color (default currentColor). */
    stroke: { type: String, default: 'currentColor' },
    /** Stroke width. */
    strokeWidth: { type: [Number, String], default: 1.5 },
  },
  setup() {
    return { ARROW_PATH_D };
  },
});
</script>
