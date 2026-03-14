<template>
  <svg
    :width="size"
    :height="size"
    viewBox="-2 -2 36 36"
    xmlns="http://www.w3.org/2000/svg"
    class="block flex-shrink-0"
    :style="{ minWidth: size + 'px', maxWidth: size + 'px', minHeight: size + 'px', maxHeight: size + 'px' }"
    role="img"
    aria-hidden="true"
  >
    <g :transform="`rotate(${angle}, 16, 16)`">
      <!-- Visible circle for selected; transparent circle when reserveCircle to keep same padding -->
      <circle
        v-if="selected || reserveCircle"
        cx="16"
        cy="16"
        r="15"
        :fill="selected ? 'white' : 'transparent'"
        :stroke="selected ? '#000' : 'transparent'"
        stroke-width="1.5"
      />
      <g transform="translate(16,2.6) scale(0.8) translate(-16,-2.6)">
        <path
          :fill="color"
          :stroke="'#000'"
          :stroke-width="selected ? 1 : 2"
          stroke-linejoin="round"
          :d="ARROW_PATH_D"
        />
      </g>
    </g>
  </svg>
</template>

<script>
import { ARROW_PATH_D } from './trackArrowMap.js';

export default {
  name: 'TrackDirectionIcon',
  props: {
    /** Hex or CSS color for the icon fill. */
    color: {
      type: String,
      default: '#6C93DE'
    },
    /** Rotation in degrees (0 = point up). Clockwise positive. */
    angle: {
      type: Number,
      default: 0
    },
    /** Icon size in pixels (width and height). */
    size: {
      type: Number,
      default: 16
    },
    /** When true, show a white circle with black border around the chevron (current/selected track). */
    selected: {
      type: Boolean,
      default: false
    },
    /** When true, reserve circle space (transparent circle) so padding matches the selected style. Use in lists. */
    reserveCircle: {
      type: Boolean,
      default: false
    }
  },
  setup() {
    return { ARROW_PATH_D };
  }
};
</script>
