<template>
  <BaseButton
    type="button"
    :variant="baseVariant"
    :color="baseColor"
    :size="baseSize"
    :disabled="disabled"
    :aria-label="copied ? 'Copied' : 'Copy'"
    :class="buttonClass"
    @click="onClick"
  >
    <CheckIcon v-if="copied" class="h-5 w-5 text-green-700 shrink-0" aria-hidden="true" />
    <template v-else>
      <ClipboardDocumentIcon class="mr-2 h-5 w-5 shrink-0" aria-hidden="true" />
      <span>{{ label }}</span>
    </template>
  </BaseButton>
</template>

<script lang="ts">
import { defineComponent } from 'vue';
import { CheckIcon, ClipboardDocumentIcon } from '@heroicons/vue/24/outline';
import BaseButton from 'platform/components/parts/BaseButton.vue';

export default defineComponent({
  name: 'CopyTextButton',
  components: { BaseButton, CheckIcon, ClipboardDocumentIcon },
  props: {
    text: { type: String, default: '' },
    label: { type: String, default: 'Copy' },
    size: {
      type: String,
      default: 'md',
      validator: (v: string) => ['sm', 'md', 'wide'].includes(v),
    },
    appearance: {
      type: String,
      default: 'default',
      validator: (v: string) => ['default', 'secondary'].includes(v),
    },
    fullWidth: { type: Boolean, default: false },
  },
  data() {
    return {
      copied: false,
      resetTimerId: null as ReturnType<typeof setTimeout> | null,
    };
  },
  computed: {
    disabled(): boolean {
      return !this.text.trim();
    },
    baseVariant(): string {
      return this.appearance === 'secondary' ? 'white' : 'secondary';
    },
    baseColor(): string {
      return 'gray';
    },
    baseSize(): string {
      if (this.size === 'sm') return 'xs';
      if (this.size === 'wide') return 'md';
      return 'sm';
    },
    buttonClass(): string {
      return this.fullWidth ? 'w-full' : '';
    },
  },
  beforeUnmount() {
    this.clearResetTimer();
  },
  methods: {
    clearResetTimer(): void {
      if (this.resetTimerId != null) {
        clearTimeout(this.resetTimerId);
        this.resetTimerId = null;
      }
    },
    onClick(): void {
      if (this.disabled) return;
      const value = this.text;
      const onSuccess = () => {
        this.copied = true;
        this.clearResetTimer();
        this.resetTimerId = setTimeout(() => {
          this.copied = false;
          this.resetTimerId = null;
        }, 2000);
      };
      navigator.clipboard.writeText(value).then(onSuccess).catch(() => { this.copyFallback(value, onSuccess); });
    },
    copyFallback(text: string, onSuccess?: () => void): void {
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
});
</script>
