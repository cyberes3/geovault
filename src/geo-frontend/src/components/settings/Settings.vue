<template>
  <div class="max-w-7xl mx-auto">
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6 mb-6">
      <h1 class="text-2xl font-bold text-gray-900">Settings</h1>
      <p class="text-gray-600 mt-1">Manage your account settings and preferences.</p>
    </div>

    <div class="flex flex-col lg:flex-row gap-6">
      <!-- Sidebar Navigation -->
      <div class="lg:w-64 flex-shrink-0">
        <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
          <nav class="space-y-1">
            <button
              @click="activeTab = 'account'"
              :class="[
                'w-full text-left px-4 py-3 rounded-md text-sm font-medium transition-colors duration-200',
                activeTab === 'account'
                  ? 'bg-blue-50 text-blue-700 border-l-4 border-blue-600'
                  : 'text-gray-700 hover:bg-gray-50'
              ]"
            >
              <div class="flex items-center">
                <svg class="w-5 h-5 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"></path>
                </svg>
                Account
              </div>
            </button>
            <button
              @click="activeTab = 'map'"
              :class="[
                'w-full text-left px-4 py-3 rounded-md text-sm font-medium transition-colors duration-200',
                activeTab === 'map'
                  ? 'bg-blue-50 text-blue-700 border-l-4 border-blue-600'
                  : 'text-gray-700 hover:bg-gray-50'
              ]"
            >
              <div class="flex items-center">
                <svg class="w-5 h-5 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
                </svg>
                Map
              </div>
            </button>
            <button
              @click="activeTab = 'sharing'"
              :class="[
                'w-full text-left px-4 py-3 rounded-md text-sm font-medium transition-colors duration-200',
                activeTab === 'sharing'
                  ? 'bg-blue-50 text-blue-700 border-l-4 border-blue-600'
                  : 'text-gray-700 hover:bg-gray-50'
              ]"
            >
              <div class="flex items-center">
                <svg class="w-5 h-5 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8.684 13.342C8.885 12.938 9 12.482 9 12c0-.482-.115-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"></path>
                </svg>
                Sharing
              </div>
            </button>
          </nav>
        </div>
      </div>

      <!-- Tab Content -->
      <div class="flex-1">
        <!-- Account Tab -->
        <div v-if="activeTab === 'account'" class="space-y-6">
          <!-- Password Change Section -->
          <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
            <h2 class="text-lg font-semibold text-gray-900 mb-4">Change Password</h2>
            <form @submit.prevent="handlePasswordChange" class="space-y-4">
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">Current Password</label>
                <input
                  v-model="passwordForm.currentPassword"
                  type="password"
                  required
                  :disabled="passwordLoading"
                  class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
                />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">New Password</label>
                <input
                  v-model="passwordForm.newPassword"
                  type="password"
                  required
                  :disabled="passwordLoading"
                  class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
                />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">Confirm New Password</label>
                <input
                  v-model="passwordForm.confirmPassword"
                  type="password"
                  required
                  :disabled="passwordLoading"
                  class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
                />
              </div>
              <div v-if="passwordMessage" :class="[
                'p-3 rounded-md text-sm',
                passwordMessageType === 'success' ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'
              ]">
                {{ passwordMessage }}
              </div>
              <button
                type="submit"
                :disabled="passwordLoading"
                class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <span v-if="passwordLoading">Changing...</span>
                <span v-else>Change Password</span>
              </button>
            </form>
          </div>

          <!-- Email Change Section -->
          <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
            <h2 class="text-lg font-semibold text-gray-900 mb-4">Change Email</h2>
            <form @submit.prevent="handleEmailChange" class="space-y-4">
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">Current Email</label>
                <input
                  :value="currentEmail"
                  type="email"
                  readonly
                  class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm bg-gray-50 text-gray-500 cursor-not-allowed"
                />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">New Email</label>
                <input
                  v-model="emailForm.newEmail"
                  type="email"
                  required
                  :disabled="emailLoading"
                  class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
                />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">Confirm New Email</label>
                <input
                  v-model="emailForm.confirmEmail"
                  type="email"
                  required
                  :disabled="emailLoading"
                  class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
                />
              </div>
              <div v-if="emailMessage" :class="[
                'p-3 rounded-md text-sm',
                emailMessageType === 'success' ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'
              ]">
                {{ emailMessage }}
              </div>
              <button
                type="submit"
                :disabled="emailLoading"
                class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <span v-if="emailLoading">Changing...</span>
                <span v-else>Change Email</span>
              </button>
            </form>
          </div>
        </div>

        <!-- Map Tab -->
        <div v-if="activeTab === 'map'" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <h2 class="text-lg font-semibold text-gray-900 mb-4">Map Settings</h2>
          <p class="text-gray-500">Map settings coming soon.</p>
        </div>

        <!-- Sharing Tab -->
        <div v-if="activeTab === 'sharing'" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
          <h2 class="text-lg font-semibold text-gray-900 mb-4">Shared Links</h2>
          <p class="text-gray-500">Shared links management coming soon.</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import axios from "axios";
import { getCookie } from "@/assets/js/auth.js";

export default {
  name: 'Settings',
  data() {
    return {
      activeTab: 'account',
      currentEmail: '',
      passwordForm: {
        currentPassword: '',
        newPassword: '',
        confirmPassword: ''
      },
      emailForm: {
        newEmail: '',
        confirmEmail: ''
      },
      passwordLoading: false,
      emailLoading: false,
      passwordMessage: '',
      passwordMessageType: '',
      emailMessage: '',
      emailMessageType: ''
    }
  },
  methods: {
    async loadCurrentEmail() {
      try {
        const response = await axios.get('/api/user/email/change/', {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        });
        if (response.data.success) {
          this.currentEmail = response.data.email || 'Not set';
        }
      } catch (error) {
        console.error('Error loading current email:', error);
        this.currentEmail = 'Error loading email';
      }
    },
    async handlePasswordChange() {
      this.passwordLoading = true;
      this.passwordMessage = '';
      this.passwordMessageType = '';

      try {
        const response = await axios.post('/api/user/password/change/', {
          current_password: this.passwordForm.currentPassword,
          new_password: this.passwordForm.newPassword,
          confirm_password: this.passwordForm.confirmPassword
        }, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        });

        if (response.data.success) {
          this.passwordMessage = response.data.message || 'Password changed successfully.';
          this.passwordMessageType = 'success';
          // Clear form
          this.passwordForm = {
            currentPassword: '',
            newPassword: '',
            confirmPassword: ''
          };
        } else {
          this.passwordMessage = response.data.error || 'Failed to change password.';
          this.passwordMessageType = 'error';
        }
      } catch (error) {
        if (error.response && error.response.data && error.response.data.error) {
          this.passwordMessage = error.response.data.error;
        } else {
          this.passwordMessage = 'An error occurred while changing your password.';
        }
        this.passwordMessageType = 'error';
      } finally {
        this.passwordLoading = false;
      }
    },
    async handleEmailChange() {
      this.emailLoading = true;
      this.emailMessage = '';
      this.emailMessageType = '';

      try {
        const response = await axios.post('/api/user/email/change/', {
          new_email: this.emailForm.newEmail,
          confirm_email: this.emailForm.confirmEmail
        }, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        });

        if (response.data.success) {
          this.emailMessage = response.data.message || 'Email changed successfully.';
          this.emailMessageType = 'success';
          this.currentEmail = response.data.email || this.emailForm.newEmail;
          // Clear form
          this.emailForm = {
            newEmail: '',
            confirmEmail: ''
          };
        } else {
          this.emailMessage = response.data.error || 'Failed to change email.';
          this.emailMessageType = 'error';
        }
      } catch (error) {
        if (error.response && error.response.data && error.response.data.error) {
          this.emailMessage = error.response.data.error;
        } else {
          this.emailMessage = 'An error occurred while changing your email.';
        }
        this.emailMessageType = 'error';
      } finally {
        this.emailLoading = false;
      }
    }
  },
  async created() {
    await this.loadCurrentEmail();
  }
}
</script>

<style scoped>
</style>


