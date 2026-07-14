import { computed, onBeforeUnmount, ref, type ComputedRef, type Ref } from 'vue';

const MOBILE_MEDIA_QUERY = '(max-width: 1023px)';

export interface UseBreakpointReturn {
  isMobile: Ref<boolean>;
  isDesktop: ComputedRef<boolean>;
}

export function useBreakpoint(query: string = MOBILE_MEDIA_QUERY): UseBreakpointReturn {
  const mediaQuery = window.matchMedia(query);
  const isMobile = ref(mediaQuery.matches);

  const onChange = (event: MediaQueryListEvent): void => {
    isMobile.value = event.matches;
  };

  mediaQuery.addEventListener('change', onChange);
  onBeforeUnmount(() => {
    mediaQuery.removeEventListener('change', onChange);
  });

  return {
    isMobile,
    isDesktop: computed(() => !isMobile.value),
  };
}
