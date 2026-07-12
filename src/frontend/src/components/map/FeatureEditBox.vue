<template>
  <div v-if="feature" class="fixed inset-0 z-50 bg-white flex flex-col w-full h-full md:absolute md:inset-auto md:bottom-16 md:right-0 md:w-96 md:max-w-md md:h-auto md:max-h-[calc(100vh-12rem)] lg:bottom-0 lg:right-4 rounded-t-xl md:rounded-lg lg:rounded-lg shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.1)] md:shadow-xl md:border-l md:border-b lg:border md:border-gray-200 lg:border-gray-200">
    <!-- Header (Sticky) -->
    <div class="sticky top-0 z-10 flex-none flex items-center justify-between px-6 py-4 border-b border-gray-200 bg-gray-50 sm:rounded-t-lg">
      <h3 class="text-lg font-medium text-gray-900 truncate">Edit Feature</h3>
      <div class="flex items-center gap-2">
        <button
          @click="emit('zoom')"
          :disabled="isSaving"
          class="text-gray-500 hover:text-blue-600 transition ease-in-out duration-150 disabled:opacity-50 disabled:cursor-not-allowed"
          title="Zoom to Feature"
        >
          <MapPinIcon class="h-5 w-5" />
        </button>
        <button
          @click="emit('cancel')"
          :disabled="isSaving"
          class="text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150 disabled:opacity-50 disabled:cursor-not-allowed"
          title="Close Edit Dialog"
        >
          <BoldXMarkIcon class="h-6 w-6" />
        </button>
      </div>
    </div>

    <!-- Scrollable Content -->
    <div class="flex-1 overflow-y-auto px-6 py-2">
      <form @submit.prevent="handleSubmit" class="space-y-4">
        <FeatureMetadataFields
          :name="formData.name"
          :description="formData.description"
          :created-date-for-input="createdDateForInput"
          :disabled="isSaving"
          @update:name="formData.name = $event"
          @update:description="formData.description = $event"
          @update:created="updateDate"
        />

        <FeatureTagEditor
          :tags="formData.tags"
          :available-tags="availableTags"
          :system-tags="systemTags"
          :disabled="isSaving"
          @update:tags="formData.tags = $event"
        />

        <FeatureStyleIconSection
          :is-point="isPoint"
          :is-line="isLine"
          :is-polygon="isPolygon"
          :current-icon-url="currentIconUrl"
          :is-custom-icon="isCustomIcon"
          :icon-upload-error="iconUploadError"
          :marker-color="formData.markerColor"
          :stroke-color="formData.strokeColor"
          :disabled="isSaving"
          @icon-selected="handleIconSelectedFromSelector"
          @icon-removed="handleRemoveIcon"
          @update:marker-color="formData.markerColor = $event"
          @update:stroke-color="formData.strokeColor = $event"
          @stroke-color-change="onStrokeColorChange"
        />

        <!-- Coordinates Section -->
        <div class="pt-2">
          <div class="flex items-center justify-center gap-4">
            <button
              type="button"
              @click="openCoordinatesDialog"
              :disabled="isSaving"
              class="text-xs text-blue-600 hover:text-blue-800 hover:underline disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center font-medium focus:outline-none"
              title="Edit Coordinates Manually"
            >
              <MapIcon class="w-3 h-3 mr-1" />
              Edit Coords
            </button>
            <button
              type="button"
              @click="openReplacementDialog"
              :disabled="isSaving"
              class="text-xs text-blue-600 hover:text-blue-800 hover:underline disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center font-medium focus:outline-none"
              title="Update Spatial Data from File"
            >
              <ArrowUpTrayIcon class="w-3 h-3 mr-1" />
              Update Geo
            </button>
          </div>
        </div>

        <!-- Account-level visibility toggle (main map only) -->
        <div
          v-if="canHideFeature && featureId"
          class="mt-3 pt-3 border-t border-gray-200"
        >
          <label class="inline-flex items-start gap-2 cursor-pointer">
            <input
              type="checkbox"
              v-model="hideOnMainMap"
              @change="handleHideToggle"
              :disabled="isSaving"
              class="mt-0.5 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
            />
            <div>
              <span class="block text-xs font-semibold text-gray-800">
                Hide this feature on the main map
              </span>
              <span class="block text-[11px] text-gray-500">
                Only affects your main map view. Collections, tag views, and public shares are unaffected.
              </span>
            </div>
          </label>
        </div>

        <!-- Error Message -->
        <div v-if="errorMessage" class="p-2 bg-red-50 border border-red-200 rounded-md">
          <p class="text-xs text-red-800">{{ errorMessage }}</p>
        </div>
      </form>
    </div>

    <!-- Footer with Action Buttons (Sticky) -->
    <div class="sticky bottom-0 z-10 flex-none flex justify-between px-6 py-4 gap-3 border-t border-gray-200 bg-gray-50 sm:rounded-b-lg">
      <BaseButton
        type="button"
        @click="handleDelete"
        :disabled="isSaving"
        variant="primary"
        color="red"
        size="sm"
        title="Delete Feature"
      >
        Delete
      </BaseButton>
      <div class="flex space-x-2 flex-1 md:flex-none justify-end">
        <BaseButton
          type="button"
          @click="emit('cancel')"
          :disabled="isSaving"
          variant="white"
          size="sm"
          class="flex-1 md:flex-none"
          title="Cancel Editing"
        >
          Cancel
        </BaseButton>
        <BaseButton
          type="button"
          @click="handleSubmit"
          :disabled="isSaving"
          variant="primary"
          color="blue"
          size="sm"
          class="flex-1 md:flex-none"
          title="Save Changes"
        >
          {{ isSaving ? 'Saving...' : 'Save' }}
        </BaseButton>
      </div>
    </div>

    <!-- Replacement Feature Dialog -->
    <ReplacementFeatureDialog
      :is-open="replacementDialogOpen"
      :feature-id="replacementFeatureId"
      @close="closeReplacementDialog"
      @applied="handleReplacementApplied"
    />

    <!-- Coordinates Edit Dialog -->
    <CoordinatesDialog
      :is-open="coordinatesDialogOpen"
      :coordinates="rawJsonInput"
      :feature="feature"
      :geometry-type="geometryType ?? undefined"
      :disabled="isSaving"
      @close="closeCoordinatesDialog"
      @save="handleCoordinatesSave"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, toRef } from 'vue';
import ReplacementFeatureDialog from './ReplacementFeatureDialog.vue';
import CoordinatesDialog from './CoordinatesDialog.vue';
import BaseButton from '@/components/parts/BaseButton.vue';
import BoldXMarkIcon from '@/components/icons/BoldXMarkIcon.vue';
import { MapIcon, ArrowUpTrayIcon, MapPinIcon } from '@heroicons/vue/24/outline';
import FeatureMetadataFields from './featureEdit/FeatureMetadataFields.vue';
import FeatureTagEditor from './featureEdit/FeatureTagEditor.vue';
import FeatureStyleIconSection from './featureEdit/FeatureStyleIconSection.vue';
import { useFeatureEditForm } from '@/composables/useFeatureEditForm';
import type { GeoJsonFeature } from '@/types/geospatial';

const props = withDefaults(defineProps<{
  feature?: GeoJsonFeature | null;
  availableTags?: string[];
  canHideFeature?: boolean;
  initialHidden?: boolean;
}>(), {
  feature: null,
  availableTags: () => [],
  canHideFeature: false,
  initialHidden: false,
});

const emit = defineEmits<{
  cancel: [];
  saved: [feature?: GeoJsonFeature];
  deleted: [feature: GeoJsonFeature];
  'visibility-change': [payload: { featureId: string | number; hidden: boolean }];
  zoom: [];
}>();

const {
  formData,
  rawJsonInput,
  isSaving,
  errorMessage,
  iconUploadError,
  currentIconUrl,
  isCustomIcon,
  hideOnMainMap,
  featureId,
  geometryType,
  isPoint,
  isLine,
  isPolygon,
  systemTags,
  createdDateForInput,
  onStrokeColorChange,
  handleIconSelectedFromSelector,
  handleRemoveIcon,
  handleSubmit,
  handleDelete,
  handleHideToggle,
  updateDate,
} = useFeatureEditForm({
  feature: toRef(props, 'feature'),
  initialHidden: toRef(props, 'initialHidden'),
  emit,
});

const availableTags = toRef(props, 'availableTags');
const canHideFeature = toRef(props, 'canHideFeature');
const feature = toRef(props, 'feature');

const replacementDialogOpen = ref(false);
const coordinatesDialogOpen = ref(false);

// ReplacementFeatureDialog declares `featureId` as a required Number; it's only ever opened
// (and thus actually reads the prop) once `openReplacementDialog` has confirmed a real id exists.
const replacementFeatureId = computed(() => featureId.value as number);

function openReplacementDialog() {
  if (!featureId.value) {
    errorMessage.value = 'Feature ID not found. Cannot update spatial data.';
    return;
  }
  replacementDialogOpen.value = true;
}

function closeReplacementDialog() {
  replacementDialogOpen.value = false;
}

function handleReplacementApplied() {
  emit('saved');
  closeReplacementDialog();
}

function openCoordinatesDialog() {
  coordinatesDialogOpen.value = true;
}

function closeCoordinatesDialog() {
  coordinatesDialogOpen.value = false;
}

function handleCoordinatesSave(coordinates: string) {
  rawJsonInput.value = coordinates;
  closeCoordinatesDialog();
}
</script>

<style scoped>
.tag-input {
  text-transform: lowercase;
}
</style>
