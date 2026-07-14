<template>
  <div
    data-app-mobile-overlay="sheet"
    :class="[
      'mobile-tracker-drawer',
      {
        'mobile-tracker-drawer--dragging': isDrawerDragging,
        'mobile-tracker-drawer--hidden': hidden
      }
    ]"
    :style="{ height: (heightPx || snapPx[0]) + 'px', maxHeight: maxHeight + 'px' }"
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

<script lang="ts">
import { defineComponent, ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';

interface DrawerDragState {
  active: boolean;
  startY: number;
  startHeight: number;
}

export default defineComponent({
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
    const snapPx = computed((): [number, number] => {
      const max = props.maxHeight;
      return [Math.round(max * 0.25), max];
    });

    const heightPx = ref(0);
    const isDrawerDragging = ref(false);
    const drawerDrag = ref<DrawerDragState>({ active: false, startY: 0, startHeight: 0 });
    let mouseMoveListener: ((e: MouseEvent) => void) | null = null;
    let mouseUpListener: (() => void) | null = null;
    let rafId: number | null = null;
    let pendingDragY: number | null = null;

    const isDrawerAtPeek = computed((): boolean => {
      const current = heightPx.value || snapPx.value[0];
      const peek = snapPx.value[0];
      return current <= peek + 2;
    });

    function collapseToPeek(): void {
      heightPx.value = snapPx.value[0];
    }

    function applyHeightFromDrag(y: number): void {
      const drag = drawerDrag.value;
      if (!drag.active) return;
      const deltaY = drag.startY - y;
      const [minH, maxH] = snapPx.value;
      let h = Math.round(drag.startHeight + deltaY);
      h = Math.max(minH, Math.min(maxH, h));
      heightPx.value = h;
    }

    function onDrawerDragStart(e: TouchEvent | MouseEvent): void {
      const y = 'touches' in e ? e.touches[0].clientY : e.clientY;
      drawerDrag.value = { active: true, startY: y, startHeight: heightPx.value };
      isDrawerDragging.value = true;
      if (!('touches' in e)) {
        mouseMoveListener = (e2: MouseEvent) => { onDrawerDragMove(e2); };
        mouseUpListener = () => { onDrawerDragEnd(); };
        document.addEventListener('mousemove', mouseMoveListener);
        document.addEventListener('mouseup', mouseUpListener);
      }
    }

    function onDrawerDragMove(e: TouchEvent | MouseEvent): void {
      if (!drawerDrag.value.active) return;
      const y = 'touches' in e ? e.touches[0].clientY : e.clientY;
      pendingDragY = y;
      rafId ??= requestAnimationFrame(() => {
        rafId = null;
        if (pendingDragY != null) {
          applyHeightFromDrag(pendingDragY);
          pendingDragY = null;
        }
      });
    }

    function onDrawerDragEnd(): void {
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
        if (mouseUpListener) document.removeEventListener('mouseup', mouseUpListener);
        mouseUpListener = null;
        mouseMoveListener = null;
      }
      const [minSnap, maxSnap] = snapPx.value;
      const current = heightPx.value;
      const mid = (minSnap + maxSnap) / 2;
      heightPx.value = current >= mid ? maxSnap : minSnap;
    }

    watch(
      () => props.maxHeight,
      (max) => {
        const snaps: [number, number] = [Math.round(max * 0.25), max];
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
        if (mouseUpListener) document.removeEventListener('mouseup', mouseUpListener);
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
});
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
