<template>
  <div
    v-show="isLoading"
    :class="[
      'absolute',
      'right-4',
      'bg-white',
      'bg-opacity-90',
      'px-4',
      'py-2',
      'rounded-lg',
      'shadow-md',
      'z-10',
      'flex',
      'items-center',
      isPublicShareMode ? 'top-20' : 'top-4'
    ]"
  >
    <Loader
      size="sm"
      layout="inline"
      message="Loading data..."
      :showMessage="true"
      :bold="false"
    />
  </div>
</template>

<script>
import Loader from '@/components/parts/Loader.vue'

export default {
  name: 'MapLoadingIndicator',
  components: {
    Loader
  },
  props: {
    isLoading: {
      type: Boolean,
      required: true
    },
    isPublicShareMode: {
      type: Boolean,
      default: false
    }
  }
}
</script>


