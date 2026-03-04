<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    xmlns="http://www.w3.org/2000/svg"
    class="inline-block flex-shrink-0"
    role="img"
    aria-hidden="true"
  >
    <g :transform="`rotate(${angle}, 16, 16)`">
      <path
        :fill="color"
        stroke="#000"
        stroke-width="1.2"
        stroke-linecap="round"
        stroke-linejoin="round"
        stroke-miterlimit="10"
        d="M29.9,28.6l-13-26c-0.3-0.7-1.4-0.7-1.8,0l-13,26c-0.2,0.4-0.1,0.8,0.2,1.1C2.5,30,3,30.1,3.4,29.9L16,25.1l12.6,4.9c0.1,0,0.2,0.1,0.4,0.1c0.3,0,0.5-0.1,0.7-0.3C30,29.4,30.1,28.9,29.9,28.6z"
      />
    </g>
  </svg>
</template>

<script>
export default {
  name: 'TrackDirectionIcon',
  props: {
    /** Hex or CSS color for the icon fill. */
    color: {
      type: String,
      default: '#3388ff'
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
    }
  }
};
</script>
