import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';

const MOBILE_MEDIA_QUERY = '(max-width: 639px)';
/** App nav = 64px, tracker title bar = 64px, small buffer = 4px; max sheet height stops at the bottom of the tracker title. */
const APP_NAV_PX = 64;
const TRACKER_HEADER_PX = 64;
const BUFFER_PX = 4;

/**
 * Mobile-view detection, mobile action-menu, and mobile drawer sizing for LiveTrackView.
 * Fully self-contained UI state - does not know about tracks, groups, or the map.
 */
export function useMobileView() {
  const isMobileView = ref(
    typeof window !== 'undefined' ? window.matchMedia(MOBILE_MEDIA_QUERY).matches : false
  );
  const isSheetOpen = ref(false);
  const windowHeight = ref(typeof window !== 'undefined' ? window.innerHeight : 800);
  const mobileDrawerRef = ref(null);
  const mobileActionsMenuOpen = ref(false);
  const mobileActionsMenuRootRef = ref(null);

  let mediaQuery = null;
  let mobileQueryListener = null;
  let mobileActionsOutsideStop = null;
  let resizeListenerAttached = false;
  let bodyOverflowBeforeLock = '';

  function closeMobileActionsMenu() {
    mobileActionsMenuOpen.value = false;
  }

  function updateWindowHeight() {
    if (typeof window === 'undefined') return;
    windowHeight.value = window.innerHeight;
  }

  function setBodyScrollLocked(locked) {
    if (typeof document === 'undefined') return;
    if (locked) {
      if (document.body.style.overflow !== 'hidden') {
        bodyOverflowBeforeLock = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
      }
      return;
    }
    document.body.style.overflow = bodyOverflowBeforeLock;
  }

  const trackerMaxHeight = computed(() =>
    Math.max(65, windowHeight.value - APP_NAV_PX - TRACKER_HEADER_PX - BUFFER_PX)
  );

  /** Collapse drawer to its 25% peek height - just set height; no close animation, no bounce. */
  function collapseDrawerToPeek() {
    if (!isMobileView.value) return;
    mobileDrawerRef.value?.collapseToPeek?.();
  }

  function getDrawerPeekHeight() {
    const snap = mobileDrawerRef.value?.snapPx?.[0];
    if (Number.isFinite(snap) && snap > 0) return snap;
    return Math.round(trackerMaxHeight.value * 0.25);
  }

  watch(isMobileView, (mobile) => {
    if (!mobile) closeMobileActionsMenu();
  });

  watch(mobileActionsMenuOpen, (open) => {
    if (mobileActionsOutsideStop) {
      mobileActionsOutsideStop();
      mobileActionsOutsideStop = null;
    }
    if (!open || typeof document === 'undefined') return;
    const handler = (e) => {
      const root = mobileActionsMenuRootRef.value;
      if (root && !root.contains(e.target)) {
        mobileActionsMenuOpen.value = false;
      }
    };
    document.addEventListener('pointerdown', handler, true);
    mobileActionsOutsideStop = () => {
      document.removeEventListener('pointerdown', handler, true);
      mobileActionsOutsideStop = null;
    };
  });

  watch([isMobileView, isSheetOpen], ([mobile, open]) => {
    setBodyScrollLocked(mobile && open);
  }, { immediate: true });

  onMounted(() => {
    if (typeof window === 'undefined') return;
    if (!resizeListenerAttached) {
      window.addEventListener('resize', updateWindowHeight);
      updateWindowHeight();
      resizeListenerAttached = true;
    }
    mediaQuery = window.matchMedia(MOBILE_MEDIA_QUERY);
    isMobileView.value = mediaQuery.matches;
    isSheetOpen.value = mediaQuery.matches;
    mobileQueryListener = (e) => {
      isMobileView.value = e.matches;
      isSheetOpen.value = e.matches;
    };
    mediaQuery.addEventListener('change', mobileQueryListener);
  });

  onBeforeUnmount(() => {
    setBodyScrollLocked(false);
    if (typeof window !== 'undefined' && resizeListenerAttached) {
      window.removeEventListener('resize', updateWindowHeight);
      resizeListenerAttached = false;
    }
    if (mobileActionsOutsideStop) {
      mobileActionsOutsideStop();
      mobileActionsOutsideStop = null;
    }
    if (mobileQueryListener && mediaQuery) {
      mediaQuery.removeEventListener('change', mobileQueryListener);
      mobileQueryListener = null;
    }
  });

  return {
    isMobileView,
    isSheetOpen,
    trackerMaxHeight,
    mobileDrawerRef,
    mobileActionsMenuOpen,
    mobileActionsMenuRootRef,
    closeMobileActionsMenu,
    collapseDrawerToPeek,
    getDrawerPeekHeight
  };
}
