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
      <div class="flex items-center justify-center py-12">
        <div class="text-center">
          <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"></path>
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"></path>
          </svg>
          <h2 class="mt-4 text-lg font-medium text-gray-900">Admin Panel</h2>
          <p class="mt-2 text-sm text-gray-500">
            This is a placeholder for the admin panel.<br>
            Admin functionality will be added here.
          </p>
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

