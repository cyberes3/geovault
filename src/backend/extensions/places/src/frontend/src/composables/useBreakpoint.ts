import { computed, onBeforeUnmount, ref } from 'vue';

const MOBILE_MEDIA_QUERY = '(max-width: 1023px)';

export function useBreakpoint(query = MOBILE_MEDIA_QUERY) {
  const mediaQuery = window.matchMedia(query);
  const isMobile = ref(mediaQuery.matches);

  const onChange = (event) => {
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
