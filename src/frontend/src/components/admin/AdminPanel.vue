<template>
  <div v-if="!isAuthorized" class="max-w-7xl mx-auto">
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6 mb-6">
      <div class="flex items-center justify-center py-12">
        <div class="text-center">
          <svg class="mx-auto h-12 w-12 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"></path>
          </svg>
          <h2 class="mt-4 text-lg font-medium text-gray-900">Access Denied</h2>
          <p class="mt-2 text-sm text-gray-500">
            You do not have permission to access this page.
          </p>
          <router-link
            to="/dashboard"
            class="mt-4 inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700"
          >
            Go to Dashboard
          </router-link>
        </div>
      </div>
    </div>
  </div>
  <div v-else class="max-w-7xl mx-auto">
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6 mb-6">
      <h1 class="text-2xl font-bold text-gray-900">Admin Panel</h1>
      <p class="text-gray-600 mt-1">System administration and management.</p>
    </div>

    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-start space-x-4">
        <div class="flex-shrink-0">
          <svg class="h-8 w-8 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z"></path>
          </svg>
        </div>
        <div class="flex-1">
          <h2 class="text-lg font-medium text-gray-900 mb-2">Django User Admin</h2>
          <p class="text-sm text-gray-600 mb-4">
            Access the Django admin interface to manage users, permissions, and other database models.
            This is the standard Django admin panel where you can create, edit, and delete user accounts,
            manage user permissions, and access other administrative functions.
          </p>
          <a
            href="/admin/"
            class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
          >
            Open Django Admin
            <svg class="ml-2 -mr-1 w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"></path>
            </svg>
          </a>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {mapState} from "vuex";

export default {
  name: 'AdminPanel',
  computed: {
    ...mapState(["userInfo"]),
    isAuthorized() {
      return this.userInfo && this.userInfo.isSuperuser === true;
    }
  },
  mounted() {
    document.title = 'Admin Panel - GeoVault';

    // Redirect if not authorized (defense in depth)
    if (!this.isAuthorized) {
      // Small delay to show the error message, then redirect
      setTimeout(() => {
        this.$router.push('/dashboard');
      }, 2000);
    }
  }
}
</script>

