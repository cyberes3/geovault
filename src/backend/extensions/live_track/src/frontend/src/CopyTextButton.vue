<template>
  <button
    type="button"
    :class="buttonClass"
    :disabled="disabled"
    :aria-label="copied ? 'Copied' : 'Copy'"
    @click="onClick"
  >
    <CheckIcon v-if="copied" class="h-5 w-5 text-green-700 shrink-0" aria-hidden="true" />
    <span v-else>Copy</span>
  </button>
</template>

<script>
import { CheckIcon } from '@heroicons/vue/24/outline';

const SIZE_CLASSES = {
  sm: 'min-w-[3.25rem] h-8 px-2 text-sm',
  md: 'min-w-[3.5rem] h-9 px-2 py-1.5 text-sm',
  wide: 'min-w-[4rem] h-10 px-3 text-sm',
};

export default {
  name: 'CopyTextButton',
  components: { CheckIcon },
  props: {
    text: { type: String, default: '' },
    size: {
      type: String,
      default: 'md',
      validator: (v) => ['sm', 'md', 'wide'].includes(v),
    },
  },
  data() {
    return {
      copied: false,
      resetTimerId: null,
    };
  },
  computed: {
    disabled() {
      return !String(this.text || '').trim();
    },
    buttonClass() {
      const base =
        'inline-flex items-center justify-center flex-shrink-0 rounded bg-gray-200 hover:bg-gray-300 ' +
        'focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 ' +
        'disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-gray-200';
      return `${base} ${SIZE_CLASSES[this.size]}`;
    },
  },
  beforeUnmount() {
    this.clearResetTimer();
  },
  methods: {
    clearResetTimer() {
      if (this.resetTimerId != null) {
        clearTimeout(this.resetTimerId);
        this.resetTimerId = null;
      }
    },
    onClick() {
      if (this.disabled) return;
      const value = String(this.text || '');
      const onSuccess = () => {
        this.copied = true;
        this.clearResetTimer();
        this.resetTimerId = setTimeout(() => {
          this.copied = false;
          this.resetTimerId = null;
        }, 2000);
      };
      if (navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(value).then(onSuccess).catch(() => this.copyFallback(value, onSuccess));
      } else {
        this.copyFallback(value, onSuccess);
      }
    },
    copyFallback(text, onSuccess) {
      const el = document.createElement('textarea');
      el.value = text;
      el.setAttribute('readonly', '');
      el.style.position = 'fixed';
      el.style.left = '-9999px';
      el.style.top = '0';
      document.body.appendChild(el);
      el.select();
      el.setSelectionRange(0, text.length);
      try {
        if (document.execCommand('copy')) onSuccess?.();
      } catch {
        // ignore
      }
      document.body.removeChild(el);
    },
  },
};
</script>
