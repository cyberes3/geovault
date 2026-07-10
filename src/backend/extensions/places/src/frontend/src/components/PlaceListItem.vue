<template>
  <div
      :class="[
        'group cursor-pointer p-3 sm:p-4 border rounded-lg transition-all',
        isSelected
          ? 'border-blue-500 bg-blue-100 shadow-sm'
          : 'border-gray-200 bg-white hover:border-blue-200 hover:bg-blue-50 hover:shadow-sm'
      ]"
      :data-place-id="place.properties.database_id"
      @click="$emit('select', place)"
      @touchend="$emit('touch-select', place, $event)"
      @mouseenter="$emit('hover', place.properties.database_id)"
      @mouseleave="$emit('hover', null)"
  >
    <div class="flex flex-col gap-2 sm:grid sm:grid-cols-[1fr_auto] sm:grid-rows-[auto_auto] sm:gap-x-2 sm:gap-y-1.5 sm:items-start">
      <span class="font-bold text-gray-900 text-base truncate min-w-0 sm:row-start-1 sm:col-start-1">
        {{ place.properties.name || 'Unnamed Place' }}
      </span>
      <div
          :class="[
            'flex items-center justify-center sm:justify-end gap-0.5 flex-shrink-0 transition-opacity sm:row-start-1 sm:col-start-2',
            isSelected ? 'opacity-100' : 'opacity-100 sm:opacity-0 sm:group-hover:opacity-100'
          ]"
          @click.stop
      >
        <button
            type="button"
            title="Edit"
            class="p-1.5 rounded text-blue-600 hover:bg-blue-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
            @click.stop="$emit('edit', place)"
        >
          <PencilSquareIcon class="w-4 h-4"/>
        </button>
        <button
            type="button"
            title="Delete"
            class="p-1.5 rounded text-red-600 hover:bg-red-100 focus:outline-none focus:ring-2 focus:ring-red-500"
            @click.stop="$emit('delete', place)"
        >
          <TrashIcon class="w-4 h-4"/>
        </button>
        <button
            type="button"
            title="Description"
            class="p-1.5 rounded text-gray-600 hover:bg-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
            @click.stop="$emit('open-description', place)"
        >
          <DocumentTextIcon class="w-4 h-4"/>
        </button>
        <button
            type="button"
            title="Open in Google Maps"
            class="group/maps inline-flex p-1.5 rounded hover:bg-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
            @click.stop="$emit('open-maps', place)"
        >
          <span class="relative inline-block w-4 h-4">
            <img :src="googleMapsIconUrl" alt="" class="absolute inset-0 w-4 h-4 opacity-0 group-hover/maps:opacity-100 transition-none" aria-hidden="true"/>
            <img :src="googleMapsIconBwUrl" alt="Open in Google Maps" class="w-4 h-4 opacity-100 group-hover/maps:opacity-0 transition-none"/>
          </span>
        </button>
      </div>
      <div class="flex flex-row flex-wrap items-center justify-center gap-2 sm:flex-col sm:items-end sm:gap-1 sm:col-start-2 sm:row-start-2">
        <span class="inline-flex items-center gap-0.5 rounded text-xs font-medium bg-gray-100 text-gray-800 px-2 py-0.5 w-fit">
          {{ locationLabel }}
          <button
              type="button"
              class="p-0.5 rounded text-gray-500 hover:text-gray-700 hover:bg-gray-200 focus:outline-none focus:ring-1 focus:ring-gray-400 disabled:pointer-events-none"
              :title="copied ? 'Copied!' : 'Copy Coordinates'"
              :disabled="copied"
              @click.stop="$emit('copy-coordinates', place)"
          >
            <CheckIcon v-if="copied" class="w-3.5 h-3.5 text-green-600"/>
            <ClipboardDocumentIcon v-else class="w-3.5 h-3.5"/>
          </button>
        </span>
        <span
            v-if="place.properties.created_at"
            class="inline-flex items-center rounded text-xs font-medium bg-gray-100 text-gray-800 px-2 py-0.5 whitespace-nowrap"
        >
          {{ createdDate }}
        </span>
      </div>
      <p
          class="text-sm text-gray-600 min-w-0 overflow-hidden sm:row-start-2 sm:col-start-1"
          style="display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3;"
      >
        {{ place.properties.description || 'No description' }}
      </p>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import {
  CheckIcon,
  ClipboardDocumentIcon,
  DocumentTextIcon,
  PencilSquareIcon,
  TrashIcon,
} from '@heroicons/vue/24/outline';
import googleMapsIconUrl from '@/assets/google-maps-icon.svg';
import googleMapsIconBwUrl from '@/assets/google-maps-icon-bw.svg';
import { formatCreatedDate, placeLocationLabel } from '@/utils/placeFormatters.js';

const props = defineProps({
  place: { type: Object, required: true },
  isSelected: { type: Boolean, default: false },
  copied: { type: Boolean, default: false },
});

defineEmits([
  'select',
  'touch-select',
  'hover',
  'edit',
  'delete',
  'open-description',
  'open-maps',
  'copy-coordinates',
]);

const locationLabel = computed(() => placeLocationLabel(props.place));
const createdDate = computed(() => formatCreatedDate(props.place.properties.created_at));
</script>
