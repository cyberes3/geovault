<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-4">Overview</h2>

    <div class="space-y-6">
      <div>
        <p class="text-gray-800">
          Welcome to the Admin Panel. Use the navigation on the left to access different administrative functions.
        </p>
      </div>
      <div>
        <a
          href="/api/pages/apps/"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-flex items-center text-sm font-medium text-blue-600 hover:text-blue-800"
        >
          More apps
          <ArrowTopRightOnSquareIcon class="ml-1 w-4 h-4" />
        </a>
        <p class="mt-1 text-sm text-gray-500">
          Additional applications beyond those linked on the dashboard.
        </p>
      </div>
    </div>
  </div>
</template>

<script>
import { ArrowTopRightOnSquareIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'OverviewTab',
  components: { ArrowTopRightOnSquareIcon }
}
</script>

