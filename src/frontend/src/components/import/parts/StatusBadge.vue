<template>
  <span v-if="item.deleting" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-orange-100 text-orange-800 sm:bg-orange-200 sm:text-orange-900">
    <Loader size="sm" layout="inline" :showMessage="false" color="#9a3412" />
    <span class="ml-1">Deleting</span>
  </span>
  <span v-else-if="item.importing" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-200 text-blue-800">
    <Loader size="sm" layout="inline" :showMessage="false" />
    <span class="ml-1">Importing</span>
  </span>
  <span v-else-if="item.imported" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-200 text-green-900">
    <CheckIcon class="w-3 h-3 mr-1" />
    Imported
  </span>
  <span v-else-if="item.processing_failed" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-200 text-red-900">
    <ExclamationCircleIcon class="w-3 h-3 mr-1" />
    Processing
  </span>
  <span v-else-if="item.queued" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-indigo-200 text-indigo-900">
    <ClockIcon class="w-3 h-3 mr-1" />
    Waiting
  </span>
  <span v-else-if="item.processing === true || (item.processing === false && item.feature_count === -1)" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-yellow-200 text-yellow-900">
    <Loader size="sm" layout="inline" :showMessage="false" color="#854d0e" />
    <span class="ml-1">Processing</span>
  </span>
  <span v-else-if="item.file_duplicate?.status === 'duplicate_in_queue' || item.file_duplicate?.status === 'duplicate_imported' || item.file_duplicate?.status === 'all_features_duplicate'" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-purple-200 text-purple-900">
    <DocumentDuplicateIcon class="w-3 h-3 mr-1" />
    Duplicate
  </span>
  <span v-else class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-200 text-blue-800">
    <CheckCircleIcon class="w-3 h-3 mr-1" />
    Ready
  </span>
</template>

<script>
import Loader from "@/components/parts/Loader.vue";
import { CheckIcon, ExclamationCircleIcon, DocumentDuplicateIcon, ClockIcon } from '@heroicons/vue/24/outline';
import { CheckCircleIcon } from '@heroicons/vue/24/solid';

export default {
  name: 'StatusBadge',
  components: {
    Loader,
    CheckIcon,
    ExclamationCircleIcon,
    DocumentDuplicateIcon,
    CheckCircleIcon,
    ClockIcon
  },
  props: {
    item: {
      type: Object,
      required: true
    }
  }
}
</script>

