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
      <div v-if="loading" class="flex items-center justify-center py-12">
        <div class="text-center">
          <div class="inline-block animate-spin rounded-full h-8 w-8 border-b-2 border-blue-500"></div>
          <p class="mt-2 text-sm text-gray-800">Loading users...</p>
        </div>
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

<script>
import { UserIcon, ArrowTopRightOnSquareIcon, ExclamationCircleIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'UsersListTab',
  components: {
    UserIcon,
    ArrowTopRightOnSquareIcon,
    ExclamationCircleIcon
  },
  data() {
    return {
      users: [],
      loading: false,
      error: null
    }
  },
  methods: {
    formatDate(dateString) {
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
      } catch (e) {
        return 'Invalid date'
      }
    },
    formatStorage(bytes) {
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
    async fetchUsers() {
      this.loading = true
      this.error = null
      
      try {
        const response = await fetch('/api/admin/users/', {
          credentials: 'include'
        })
        
        if (!response.ok) {
          if (response.status === 403) {
            throw new Error('You do not have permission to view users.')
          }
          throw new Error(`HTTP error! status: ${response.status}`)
        }
        
        const data = await response.json()
        this.users = data.users || []
      } catch (error) {
        console.error('Failed to fetch users:', error)
        this.error = error.message || 'Failed to load users. Please try again later.'
      } finally {
        this.loading = false
      }
    }
  },
  mounted() {
    this.fetchUsers()
  }
}
</script>

