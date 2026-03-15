<template>
  <div
    :class="[
      'mobile-tracker-drawer',
      {
        'mobile-tracker-drawer--dragging': isDrawerDragging,
        'mobile-tracker-drawer--hidden': hidden
      }
    ]"
    :style="{ height: (heightPx || snapPx[0] || 200) + 'px', maxHeight: maxHeight + 'px' }"
    class="flex flex-col min-h-0"
  >
    <div
      class="mobile-drawer-handle"
      role="button"
      tabindex="0"
      aria-label="Drag to Resize"
      @touchstart.passive="onDrawerDragStart"
      @touchmove.prevent="onDrawerDragMove"
      @touchend="onDrawerDragEnd"
      @mousedown="onDrawerDragStart"
    >
      <div class="mobile-drawer-handle-bar" />
    </div>
    <div class="flex-1 min-h-0 overflow-hidden flex flex-col relative">
      <slot :at-peek="isDrawerAtPeek" />
    </div>
  </div>
</template>

<script>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';

export default {
  name: 'MobileMapDrawer',
  props: {
    /** Max height in px (e.g. viewport minus header). */
    maxHeight: {
      type: Number,
      required: true
    },
    /** Initial snap index: 0 = peek (25%), 1 = max. Default 0. */
    initialSnapIndex: {
      type: Number,
      default: 0
    },
    /** Keep mounted but visually/pointer hidden (used when sidebars are open). */
    hidden: {
      type: Boolean,
      default: false
    }
  },
  emits: [],
  setup(props, { expose }) {
    const snapPx = computed(() => {
      const max = props.maxHeight;
      return [Math.round(max * 0.25), max];
    });

    const heightPx = ref(0);
    const isDrawerDragging = ref(false);
    const drawerDrag = ref({ active: false, startY: 0, startHeight: 0 });
    let mouseMoveListener = null;
    let mouseUpListener = null;
    let rafId = null;
    let pendingDragY = null;

    const isDrawerAtPeek = computed(() => {
      const current = heightPx.value || snapPx.value[0];
      const peek = snapPx.value[0];
      return current <= peek + 2;
    });

    function collapseToPeek() {
      const snaps = snapPx.value;
      if (snaps[0] != null) heightPx.value = snaps[0];
    }

    function applyHeightFromDrag(y) {
      const drag = drawerDrag.value;
      if (!drag.active) return;
      const deltaY = drag.startY - y;
      const snaps = snapPx.value;
      const minH = snaps[0];
      const maxH = snaps[1];
      let h = Math.round(drag.startHeight + deltaY);
      h = Math.max(minH, Math.min(maxH, h));
      heightPx.value = h;
    }

    function onDrawerDragStart(e) {
      const y = e.touches ? e.touches[0].clientY : e.clientY;
      drawerDrag.value = { active: true, startY: y, startHeight: heightPx.value };
      isDrawerDragging.value = true;
      if (!e.touches) {
        mouseMoveListener = (e2) => onDrawerDragMove(e2);
        mouseUpListener = () => onDrawerDragEnd();
        document.addEventListener('mousemove', mouseMoveListener);
        document.addEventListener('mouseup', mouseUpListener);
      }
    }

    function onDrawerDragMove(e) {
      if (!drawerDrag.value.active) return;
      const y = e.touches ? e.touches[0].clientY : e.clientY;
      pendingDragY = y;
      if (rafId == null) {
        rafId = requestAnimationFrame(() => {
          rafId = null;
          if (pendingDragY != null) {
            applyHeightFromDrag(pendingDragY);
            pendingDragY = null;
          }
        });
      }
    }

    function onDrawerDragEnd() {
      if (!drawerDrag.value.active) return;
      if (rafId != null) {
        cancelAnimationFrame(rafId);
        rafId = null;
      }
      if (pendingDragY != null) applyHeightFromDrag(pendingDragY);
      pendingDragY = null;
      drawerDrag.value = { active: false, startY: 0, startHeight: 0 };
      isDrawerDragging.value = false;
      if (mouseMoveListener) {
        document.removeEventListener('mousemove', mouseMoveListener);
        document.removeEventListener('mouseup', mouseUpListener);
        mouseUpListener = null;
        mouseMoveListener = null;
      }
      const snaps = snapPx.value;
      const current = heightPx.value;
      const mid = (snaps[0] + snaps[1]) / 2;
      heightPx.value = current >= mid ? snaps[1] : snaps[0];
    }

    watch(
      () => props.maxHeight,
      (max) => {
        const snaps = [Math.round(max * 0.25), max];
        if (heightPx.value === 0) heightPx.value = snaps[props.initialSnapIndex] ?? snaps[0];
      },
      { immediate: true }
    );

    onMounted(() => {
      const snaps = snapPx.value;
      if (heightPx.value === 0) heightPx.value = snaps[props.initialSnapIndex] ?? snaps[0];
    });

    onBeforeUnmount(() => {
      if (rafId != null) cancelAnimationFrame(rafId);
      if (mouseMoveListener) {
        document.removeEventListener('mousemove', mouseMoveListener);
        document.removeEventListener('mouseup', mouseUpListener);
      }
    });

    expose({
      collapseToPeek,
      isDrawerAtPeek,
      heightPx,
      snapPx
    });

    return {
      heightPx,
      snapPx,
      isDrawerDragging,
      isDrawerAtPeek,
      onDrawerDragStart,
      onDrawerDragMove,
      onDrawerDragEnd,
      collapseToPeek
    };
  }
};
</script>

<style scoped>
.mobile-tracker-drawer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 10;
  background: #fff;
  border: 1px solid #3b82f6;
  border-bottom: none;
  border-radius: 16px 16px 0 0;
  box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.1);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: none;
}
.mobile-tracker-drawer--dragging {
  transition: none;
  will-change: height;
}

.mobile-tracker-drawer--hidden {
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
}

.mobile-drawer-handle {
  flex-shrink: 0;
  padding: 14px 16px 10px;
  cursor: grab;
  touch-action: none;
  display: flex;
  justify-content: center;
}
.mobile-drawer-handle:active {
  cursor: grabbing;
}

.mobile-drawer-handle-bar {
  width: 36px;
  height: 4px;
  border-radius: 2px;
  background: rgba(0, 0, 0, 0.28);
}

</style>
