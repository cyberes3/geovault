<template>
  <div class="border border-gray-200 rounded-lg p-3 space-y-3 bg-gray-50/50">
    <h3 class="text-sm font-semibold text-gray-800">Sharing</h3>
    <slot />
    <div v-if="!readOnly" class="space-y-2">
      <label class="text-sm font-medium text-gray-700">{{ visibilityLabel }}</label>
      <select
        :value="visibility"
        :disabled="disabled"
        class="select-custom w-full border border-gray-300 px-3 py-2 rounded-md focus:outline-none"
        @change="$emit('update:visibility', ($event.target && $event.target.value) || 'private')"
      >
        <option value="private">{{ privateLabel }}</option>
        <option value="shared">Shared with specific users</option>
        <option value="public">Public (all authenticated users)</option>
      </select>
    </div>
    <div v-if="!readOnly && visibility === 'shared'" class="space-y-2">
      <ScrollingSelect
        label="Shared with (click to add or remove)"
        :items="sharedWithSelectItems"
        :selected-values="sharedWithSelectValues"
        :loading="loadingUsers"
        :disabled="disabled"
        max-height="12rem"
        empty-message="No other users found"
        @select="onSharedWithSelect"
      />
    </div>
    <template v-if="!readOnly && variant === 'track'">
      <div class="space-y-2">
        <div class="flex items-center gap-3">
          <ToggleButton
            :model-value="allowGroupReshare"
            label="Allow adding to groups"
            size="md"
            :disabled="disabled"
            @update:model-value="$emit('update:allowGroupReshare', $event)"
          />
          <label class="text-sm font-medium text-gray-700 cursor-pointer" @click="!disabled && $emit('update:allowGroupReshare', !allowGroupReshare)">Allow adding to groups</label>
        </div>
        <p class="text-xs text-amber-700">When on, people who have access to this tracker can add it to their groups. Groups may share the tracker with more users or world links.</p>
      </div>
      <div class="space-y-2">
        <div class="flex items-center gap-3">
          <ToggleButton
            :model-value="shareParamsWithRecipients"
            label="Allow viewing parameters (shared users)"
            size="md"
            :disabled="disabled"
            @update:model-value="$emit('update:shareParamsWithRecipients', $event)"
          />
          <label class="text-sm font-medium text-gray-700 cursor-pointer" @click="!disabled && $emit('update:shareParamsWithRecipients', !shareParamsWithRecipients)">Allow viewing parameters</label>
        </div>
        <p class="text-xs text-gray-500">When on, people you share this tracker with can see extended parameters (e.g. in Latest params). Serial is never shared.</p>
      </div>
    </template>
    <div v-if="!readOnly" class="space-y-2">
      <div class="flex items-center gap-3">
        <ToggleButton
          :model-value="worldShareEnabled"
          label="World share link"
          size="md"
          :disabled="disabled"
          @update:model-value="$emit('update:worldShareEnabled', $event)"
        />
        <label class="text-sm font-medium text-gray-700 cursor-pointer" @click="!disabled && $emit('update:worldShareEnabled', !worldShareEnabled)">{{ worldShareLabel }}</label>
      </div>
      <p class="text-xs text-gray-500">{{ worldShareDescription }}</p>
    </div>
    <div v-if="fullInternalShareUrl" class="space-y-2">
      <p class="text-xs text-gray-500">{{ internalShareDescription }}</p>
      <CopyTextButton
        :text="fullInternalShareUrl"
        label="Copy Internal Link"
        size="wide"
        appearance="secondary"
        full-width
      />
    </div>
    <template v-if="!readOnly && variant === 'track' && worldShareEnabled">
      <div class="space-y-2">
        <div class="flex items-center gap-3">
          <ToggleButton
            :model-value="shareParamsWithWorld"
            label="Allow viewing parameters (world link)"
            size="md"
            :disabled="disabled"
            @update:model-value="$emit('update:shareParamsWithWorld', $event)"
          />
          <label class="text-sm font-medium text-gray-700 cursor-pointer" @click="!disabled && $emit('update:shareParamsWithWorld', !shareParamsWithWorld)">Allow viewing parameters</label>
        </div>
        <p class="text-xs text-gray-500">When on, anyone with the world share link can see extended parameters. Serial is never shared.</p>
      </div>
    </template>
    <div v-if="!readOnly && worldShareEnabled && fullWorldShareUrl" class="flex gap-2 items-center">
      <input
        :value="fullWorldShareUrl"
        readonly
        class="flex-1 px-3 py-2 text-sm border border-gray-300 rounded-md bg-gray-50 font-mono"
      />
      <CopyTextButton :text="fullWorldShareUrl" size="wide" />
    </div>
  </div>
</template>

<script>
import ScrollingSelect from 'platform/components/parts/ScrollingSelect.vue';
import ToggleButton from 'platform/components/parts/ToggleButton.vue';
import CopyTextButton from './CopyTextButton.vue';

export default {
  name: 'SharingSection',
  components: { CopyTextButton, ScrollingSelect, ToggleButton },
  props: {
    /** 'track' | 'group' – controls labels and track-only options */
    variant: { type: String, default: 'track' },
    visibility: { type: String, default: 'private' },
    sharedWithSelectItems: { type: Array, default: () => [] },
    sharedWithSelectValues: { type: Array, default: () => [] },
    loadingUsers: { type: Boolean, default: false },
    worldShareEnabled: { type: Boolean, default: false },
    worldShareUrl: { type: String, default: '' },
    /** Full URL to show and copy (e.g. origin + worldShareUrl). Parent should pass this. */
    fullWorldShareUrl: { type: String, default: '' },
    /** Full authenticated share URL available to users who already have access. */
    fullInternalShareUrl: { type: String, default: '' },
    readOnly: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    shareParamsWithRecipients: { type: Boolean, default: false },
    shareParamsWithWorld: { type: Boolean, default: false },
    allowGroupReshare: { type: Boolean, default: false },
  },
  emits: ['update:visibility', 'update:sharedWithEmails', 'update:worldShareEnabled', 'update:shareParamsWithRecipients', 'update:shareParamsWithWorld', 'update:allowGroupReshare'],
  computed: {
    visibilityLabel() {
      return this.variant === 'group' ? 'Who can see this group' : 'Who can see and add this tracker';
    },
    privateLabel() {
      return 'Private (only me)';
    },
    worldShareLabel() {
      return 'World share link';
    },
    worldShareDescription() {
      return this.variant === 'group'
        ? "When on, anyone with the link can view this group's tracks on a read-only map (no login required)."
        : 'When on, anyone with the link can view this tracker on a read-only map (no login required).';
    },
    internalShareDescription() {
      return this.variant === 'group'
        ? 'This internal link can be used by GeoVault users who already have access to this group through sharing or public visibility.'
        : 'This internal link can be used by GeoVault users who already have access to this tracker through sharing or public visibility.';
    },
  },
  methods: {
    onSharedWithSelect(item) {
      const email = (item && (item.label ?? item.value)) ? String(item.label || item.value).trim().toLowerCase() : '';
      if (!email) return;
      const current = this.sharedWithSelectValues || [];
      const has = current.some((e) => String(e || '').toLowerCase() === email);
      if (has) {
        this.$emit('update:sharedWithEmails', current.filter((e) => String(e || '').toLowerCase() !== email));
      } else {
        this.$emit('update:sharedWithEmails', [...current, email]);
      }
    },
  },
};
</script>
