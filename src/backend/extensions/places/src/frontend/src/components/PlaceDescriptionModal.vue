<template>
  <BaseModal
      :is-open="!!place"
      :title="place?.properties?.name || 'Unnamed Place'"
      :full-screen-mobile="true"
      max-width="2xl"
      @close="$emit('close')"
  >
    <div class="flex flex-col h-full">
      <div class="flex-1 p-4 sm:p-6 space-y-4">
        <textarea
            v-if="editing"
            id="description-edit"
            ref="descriptionTextarea"
            :value="draft"
            class="block w-full min-h-[200px] px-3 py-2 border border-gray-300 rounded-lg shadow-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent text-sm resize-none"
            placeholder="Add a description..."
            aria-label="Description"
            @input="$emit('update:draft', ($event.target as HTMLTextAreaElement).value)"
        />
        <div v-else class="prose prose-sm max-w-none text-gray-700">
          <p class="whitespace-pre-wrap">
            {{ place?.properties?.description || 'No description provided for this place.' }}
          </p>
        </div>
      </div>
    </div>
    <template #footer>
      <template v-if="editing">
        <BaseButton type="button" variant="white" @click="$emit('cancel-edit')">
          Cancel
        </BaseButton>
        <BaseButton
            type="button"
            variant="primary"
            color="blue"
            :disabled="saving"
            @click="$emit('save')"
        >
          {{ saving ? 'Saving...' : 'Save Changes' }}
        </BaseButton>
      </template>
      <template v-else>
        <BaseButton type="button" variant="white" @click="$emit('close')">
          Close
        </BaseButton>
        <BaseButton type="button" variant="primary" color="blue" @click="$emit('start-edit')">
          <PencilSquareIcon class="h-4 w-4 mr-1.5 inline"/>
          Edit description
        </BaseButton>
      </template>
    </template>
  </BaseModal>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { PencilSquareIcon } from '@heroicons/vue/24/outline';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import type { PlaceFeature } from '@/types/places';

withDefaults(defineProps<{
  place?: PlaceFeature | null;
  editing?: boolean;
  draft?: string;
  saving?: boolean;
}>(), {
  place: null,
  editing: false,
  draft: '',
  saving: false,
});

defineEmits<{
  close: [];
  'start-edit': [];
  'cancel-edit': [];
  save: [];
  'update:draft': [value: string];
}>();

const descriptionTextarea = ref<HTMLTextAreaElement | null>(null);
defineExpose({ descriptionTextarea });
</script>
