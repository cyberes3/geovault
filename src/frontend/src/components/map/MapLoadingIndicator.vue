<template>
  <div
    v-show="isLoading"
    class="absolute right-4 top-4 z-10 flex items-center rounded-lg border border-gray-200 bg-white px-4 py-2"
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
    }
  }
}
</script>



