<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <!-- Django User Admin Link at the top -->
    <div class="mb-6 pb-6 border-b border-gray-200">
      <div class="flex items-start space-x-4">
        <div class="flex-shrink-0">
          <UserIcon class="h-8 w-8 text-blue-500" />
        </div>
        <div class="flex-1">
          <h2 class="text-lg font-medium text-gray-900 mb-2">Django User Admin</h2>
          <p class="text-sm text-gray-800 mb-4">
            Access the Django admin interface to manage users, permissions, and other database models.
            This is the standard Django admin panel where you can create, edit, and delete user accounts,
            manage user permissions, and access other administrative functions.
          </p>
          <a
            href="/admin/"
            class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
          >
            Open Django Admin
            <ArrowTopRightOnSquareIcon class="ml-2 -mr-1 w-4 h-4" />
          </a>
        </div>
      </div>
    </div>

    <!-- Users List -->
    <div>
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Users</h2>
      
      <!-- Loading State -->
      <div v-if="loading" class="py-12">
        <Loader size="md" layout="centered" message="Loading users..." />
      </div>

      <!-- Error State -->
      <div v-else-if="error" class="bg-red-50 border border-red-200 rounded-md p-4">
        <div class="flex">
          <div class="flex-shrink-0">
            <ExclamationCircleIcon class="h-5 w-5 text-red-400" />
          </div>
          <div class="ml-3">
            <h3 class="text-sm font-medium text-red-800">Error loading users</h3>
            <p class="mt-2 text-sm text-red-700">{{ error }}</p>
          </div>
        </div>
      </div>

      <!-- Users List -->
      <div v-else-if="users.length > 0" class="flex flex-col">
        <!-- Header Row (Desktop only) -->
        <div class="hidden md:flex bg-gray-50 border-b border-gray-200">
          <div class="flex-1 md:px-3 md:py-3 lg:px-6 lg:py-4 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Email</div>
          <div class="flex-1 md:px-3 md:py-3 lg:px-6 lg:py-4 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Last Activity</div>
          <div class="flex-1 md:px-3 md:py-3 lg:px-6 lg:py-4 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Creation Date</div>
          <div class="flex-1 md:px-3 md:py-3 lg:px-6 lg:py-4 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Features</div>
          <div class="flex-1 md:px-3 md:py-3 lg:px-6 lg:py-4 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Shares</div>
          <div class="flex-1 md:px-3 md:py-3 lg:px-6 lg:py-4 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Storage</div>
        </div>

        <!-- Items -->
        <div class="flex flex-col space-y-3 md:space-y-0 md:divide-y md:divide-gray-200">
          <div v-for="user in users" :key="user.id" class="flex flex-col md:flex-row md:items-center p-3 md:p-0 border border-gray-200 md:border-0 rounded-lg md:rounded-none hover:bg-gray-50 transition-colors">
            <div class="flex-1 mb-2 md:mb-0 md:px-3 md:py-3 lg:px-6 lg:py-4">
              <div class="md:hidden text-xs font-semibold text-gray-900 uppercase tracking-wider mb-1">Email</div>
              <div class="text-xs sm:text-sm text-gray-900 break-words">{{ user.email || 'N/A' }}</div>
            </div>
            <div class="flex-1 mb-2 md:mb-0 md:px-3 md:py-3 lg:px-6 lg:py-4">
              <div class="md:hidden text-xs font-semibold text-gray-900 uppercase tracking-wider mb-1">Last Activity</div>
              <div class="text-xs sm:text-sm text-gray-800">{{ formatDate(user.last_activity) }}</div>
            </div>
            <div class="flex-1 mb-2 md:mb-0 md:px-3 md:py-3 lg:px-6 lg:py-4">
              <div class="md:hidden text-xs font-semibold text-gray-900 uppercase tracking-wider mb-1">Creation Date</div>
              <div class="text-xs sm:text-sm text-gray-800">{{ formatDate(user.date_joined) }}</div>
            </div>
            <div class="flex-1 mb-2 md:mb-0 md:px-3 md:py-3 lg:px-6 lg:py-4">
              <div class="md:hidden text-xs font-semibold text-gray-900 uppercase tracking-wider mb-1">Features</div>
              <div class="text-xs sm:text-sm text-gray-800">{{ user.feature_count }}</div>
            </div>
            <div class="flex-1 mb-2 md:mb-0 md:px-3 md:py-3 lg:px-6 lg:py-4">
              <div class="md:hidden text-xs font-semibold text-gray-900 uppercase tracking-wider mb-1">Shares</div>
              <div class="text-xs sm:text-sm text-gray-800">{{ user.share_count }}</div>
            </div>
            <div class="flex-1 md:px-3 md:py-3 lg:px-6 lg:py-4">
              <div class="md:hidden text-xs font-semibold text-gray-900 uppercase tracking-wider mb-1">Storage</div>
              <div class="text-xs sm:text-sm text-gray-800">{{ formatStorage(user.storage_bytes) }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- Empty State -->
      <div v-else class="text-center py-12">
        <p class="text-gray-800">No users found.</p>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue'
import { UserIcon, ArrowTopRightOnSquareIcon, ExclamationCircleIcon } from '@heroicons/vue/24/outline';
import { listUsers, type AdminUser } from '@/api/services/adminApi';
import { ApiError, getApiErrorMessage } from '@/utils/apiError';
import Loader from '@/components/parts/Loader.vue';

export default defineComponent({
  name: 'UsersListTab',
  components: {
    UserIcon,
    ArrowTopRightOnSquareIcon,
    ExclamationCircleIcon,
    Loader
  },
  data() {
    return {
      users: [] as AdminUser[],
      loading: false,
      error: null as string | null
    }
  },
  methods: {
    formatDate(dateString: string | null): string {
      if (!dateString) {
        return 'Never'
      }
      try {
        const date = new Date(dateString)
        return date.toLocaleDateString('en-US', {
          year: 'numeric',
          month: 'short',
          day: 'numeric',
          hour: '2-digit',
          minute: '2-digit'
        })
      } catch {
        return 'Invalid date'
      }
    },
    formatStorage(bytes: number | null | undefined): string {
      if (bytes === null || bytes === undefined) {
        return '0 B'
      }
      
      const kb = 1024
      const mb = kb * 1024
      const gb = mb * 1024
      
      if (bytes >= gb) {
        return (bytes / gb).toFixed(2) + ' GB'
      } else if (bytes >= mb) {
        return (bytes / mb).toFixed(2) + ' MB'
      } else if (bytes >= kb) {
        return (bytes / kb).toFixed(2) + ' KB'
      } else {
        return bytes + ' B'
      }
    },
    async fetchUsers(): Promise<void> {
      this.loading = true
      this.error = null
      
      try {
        this.users = await listUsers()
      } catch (error) {
        console.error('Failed to fetch users:', error)
        this.error = ApiError.from(error).status === 403
          ? 'You do not have permission to view users.'
          : getApiErrorMessage(error, 'Failed to load users. Please try again later.')
      } finally {
        this.loading = false
      }
    }
  },
  mounted() {
    void this.fetchUsers()
  }
})
</script>

