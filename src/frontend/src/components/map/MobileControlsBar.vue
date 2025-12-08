<template>
  <div class="lg:hidden bg-white border-b border-gray-200 px-4 py-3 flex justify-between items-center">
    <button
      class="p-2 text-gray-600 hover:text-gray-900 rounded-md hover:bg-gray-100 focus:outline-none"
      title="Features"
      @click="$emit('toggle-features')"
    >
      <ListBulletIcon class="w-6 h-6"/>
    </button>
    <div class="text-sm font-medium text-gray-900 max-w-[50%] text-center leading-tight flex flex-col items-center justify-center">
      <template v-if="isPublicShareMode">
        <div v-if="publicShareTag" class="flex items-center justify-center gap-1 w-full">
          <ShareIcon class="w-4 h-4 text-blue-500 flex-shrink-0"/>
          <span class="line-clamp-2">Tag: {{ publicShareTag }}</span>
        </div>
        <div v-else-if="publicShareCollectionName" class="flex items-center justify-center gap-1 w-full">
          <ShareIcon class="w-4 h-4 text-blue-500 flex-shrink-0"/>
          <span class="line-clamp-2">Collection: {{ publicShareCollectionName }}</span>
        </div>
        <div v-else class="flex items-center justify-center gap-1 w-full">
          <ShareIcon class="w-4 h-4 text-blue-500 flex-shrink-0"/>
          <span>Shared Map</span>
        </div>
      </template>
      <template v-else>
        <span class="line-clamp-2">{{ collectionName || 'Map' }}</span>
      </template>
    </div>
    <button
      class="p-2 text-gray-600 hover:text-gray-900 rounded-md hover:bg-gray-100 focus:outline-none"
      title="Map Controls"
      @click="$emit('toggle-controls')"
    >
      <Cog6ToothIcon class="w-6 h-6"/>
    </button>
  </div>
</template>

<script>
import { ListBulletIcon, Cog6ToothIcon, ShareIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'MobileControlsBar',
  components: {
    ListBulletIcon,
    Cog6ToothIcon,
    ShareIcon
  },
  props: {
    isPublicShareMode: {
      type: Boolean,
      default: false
    },
    publicShareTag: {
      type: String,
      default: null
    },
    publicShareCollectionName: {
      type: String,
      default: null
    },
    collectionName: {
      type: String,
      default: null
    }
  },
  emits: ['toggle-features', 'toggle-controls']
};
</script>

