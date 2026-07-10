<template>
  <BaseModal
      :is-open="isOpen"
      title="Map Layer"
      max-width="md"
      fit-content-height
      :full-screen-mobile="false"
      @close="$emit('close')"
  >
    <div class="p-4 sm:p-6">
      <label :for="selectId" class="block text-sm font-medium text-gray-700 mb-2">Basemap</label>
      <select
          :id="selectId"
          :value="selectedBaseSourceId"
          class="select-custom w-full px-3 py-2 text-sm border border-gray-300 rounded-lg shadow-sm focus:outline-none"
          :disabled="baseSourceOptions.length === 0"
          @change="$emit('update:selectedBaseSourceId', $event.target.value)"
      >
        <option v-for="option in baseSourceOptions" :key="option.id" :value="option.id">
          {{ option.name }}
        </option>
      </select>
    </div>
    <template #footer>
      <BaseButton type="button" variant="white" @click="$emit('close')">
        Close
      </BaseButton>
    </template>
  </BaseModal>
</template>

<script setup>
import BaseButton from 'platform/components/parts/BaseButton.vue';
import BaseModal from 'platform/components/parts/BaseModal.vue';

defineProps({
  isOpen: { type: Boolean, required: true },
  selectId: { type: String, required: true },
  selectedBaseSourceId: { type: String, required: true },
  baseSourceOptions: { type: Array, required: true },
});

defineEmits(['close', 'update:selectedBaseSourceId']);
</script>
