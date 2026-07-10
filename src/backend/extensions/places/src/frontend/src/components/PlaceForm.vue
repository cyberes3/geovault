<template>
  <div class="max-w-4xl mx-auto p-4 sm:p-6 space-y-4">
    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">
          Name <span class="text-red-500">*</span>
        </label>
        <input
            :value="name"
            type="text"
            placeholder="Place name"
            class="w-full border border-gray-300 px-4 py-2 rounded-lg shadow-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all sm:text-sm disabled:opacity-60 disabled:cursor-not-allowed"
            :disabled="loading"
            @input="$emit('update:name', $event.target.value)"
        />
      </div>
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">Description</label>
        <textarea
            :value="description"
            rows="4"
            placeholder="Optional description"
            class="w-full border border-gray-300 px-4 py-2 rounded-lg shadow-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all sm:text-sm resize-none disabled:opacity-60 disabled:cursor-not-allowed"
            :disabled="loading"
            @input="$emit('update:description', $event.target.value)"
        />
      </div>
    </div>

    <div class="space-y-1.5">
      <div class="flex items-center gap-2">
        <label class="text-xs font-semibold text-gray-500 uppercase tracking-wide">
          Coordinates or Address <span class="text-red-500">*</span>
        </label>
        <Loader v-if="isGeocoding" size="sm" :show-message="false" class="!py-0 !mt-0"/>
        <span v-if="coordinateError" class="text-xs text-red-600">{{ coordinateError }}</span>
      </div>
      <div class="flex flex-row flex-wrap gap-2 items-center">
        <div class="flex flex-1 min-w-[120px] items-center gap-2">
          <input
              :value="coordinatesInput"
              type="text"
              placeholder="37.7749, -122.4194"
              class="flex-1 min-w-0 h-10 px-3 border border-gray-300 rounded-lg shadow-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 outline-none transition-all sm:text-sm disabled:opacity-60 disabled:cursor-not-allowed"
              :disabled="loading"
              @input="$emit('coordinates-input', $event.target.value)"
          />
          <button
              type="button"
              class="h-10 w-10 flex-shrink-0 flex items-center justify-center rounded-lg text-gray-500 hover:bg-gray-100 hover:text-gray-700 transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
              :disabled="loading"
              title="Parse Coordinates or Address"
              @click="$emit('validate-coordinates')"
          >
            <ArrowPathIcon class="w-5 h-5"/>
          </button>
        </div>
        <BaseButton
            type="button"
            variant="white"
            size="sm"
            class="w-full sm:w-auto justify-center"
            :disabled="isGettingLocation || loading"
            title="Use Current Location"
            @click="$emit('use-location')"
        >
          <Loader v-if="isGettingLocation" size="sm" layout="inline" :show-message="false"/>
          <MapPinIcon v-else class="w-5 h-5 text-gray-700"/>
          <span class="ml-1.5">Use my location</span>
        </BaseButton>
      </div>
    </div>

    <div class="flex flex-wrap gap-3 pt-2">
      <BaseButton
          type="button"
          variant="primary"
          color="blue"
          size="sm"
          :disabled="saving || loading || !name.trim() || !coordinatesInput.trim()"
          @click="$emit('save')"
      >
        <Loader v-if="saving" size="sm" layout="inline" :show-message="false" class="mr-2"/>
        {{ isEdit ? 'Update place' : 'Save place' }}
      </BaseButton>
      <BaseButton type="button" variant="white" size="sm" :disabled="loading" @click="$emit('cancel')">
        Cancel
      </BaseButton>
    </div>
  </div>
</template>

<script setup>
import { ArrowPathIcon, MapPinIcon } from '@heroicons/vue/24/outline';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import Loader from 'platform/components/parts/Loader.vue';

defineProps({
  name: { type: String, default: '' },
  description: { type: String, default: '' },
  coordinatesInput: { type: String, default: '' },
  coordinateError: { type: String, default: '' },
  isGeocoding: { type: Boolean, default: false },
  isGettingLocation: { type: Boolean, default: false },
  saving: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  isEdit: { type: Boolean, default: false },
});

defineEmits([
  'update:name',
  'update:description',
  'coordinates-input',
  'validate-coordinates',
  'use-location',
  'save',
  'cancel',
]);
</script>
