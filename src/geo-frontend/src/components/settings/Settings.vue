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
            <h2 class="text-lg font-semibold text-gray-900 mb-4">Email Address</h2>
            
            <!-- Current Email Status -->
            <div class="mb-6 p-4 bg-gray-50 rounded-md">
              <div class="flex items-center justify-between">
                <div>
                  <p class="text-sm font-medium text-gray-700">Current Email</p>
                  <p class="text-sm text-gray-900 mt-1">{{ currentEmail || 'Not set' }}</p>
                  <div v-if="emailStatus" class="mt-2 flex items-center gap-2">
                    <span v-if="emailStatus.verified" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800">
                      Verified
                    </span>
                    <span v-else class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-yellow-100 text-yellow-800">
                      Unverified
                    </span>
                  </div>
                </div>
                <div class="relative" v-if="emailStatus && !emailStatus.verified">
                  <button
                    @click="handleResendVerification"
                    :disabled="resendLoading || resendCooldown > 0"
                    :title="resendCooldown > 0 ? `Please wait ${resendCooldown} second${resendCooldown !== 1 ? 's' : ''} before resending` : 'Resend verification email'"
                    class="inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed relative"
                  >
                    <span v-if="resendLoading">Sending...</span>
                    <span v-else-if="resendCooldown > 0">Resend ({{ resendCooldown }}s)</span>
                    <span v-else>Resend Verification</span>
                  </button>
                  <div
                    v-if="resendCooldown > 0"
                    class="absolute z-10 px-2 py-1 text-xs text-white bg-gray-900 rounded shadow-lg bottom-full mb-2 left-1/2 transform -translate-x-1/2 whitespace-nowrap pointer-events-none"
                  >
                    Please wait {{ resendCooldown }} second{{ resendCooldown !== 1 ? 's' : '' }} before resending
                    <div class="absolute top-full left-1/2 transform -translate-x-1/2 -mt-1">
                      <div class="border-4 border-transparent border-t-gray-900"></div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- Unverified Email Notice -->
            <div v-if="emailStatus && !emailStatus.verified" class="mb-4 p-4 bg-yellow-50 border border-yellow-200 rounded-md">
              <p class="text-sm text-yellow-800">
                <strong>Email Verification Required:</strong> Your email address is not yet verified. Please check your inbox and click the verification link to complete the process.
              </p>
            </div>

            <!-- Change Email Form -->
            <div class="border-t border-gray-200 pt-6">
              <h3 class="text-md font-medium text-gray-900 mb-4">Change Email Address</h3>
              <form @submit.prevent="handleEmailChange" class="space-y-4">
                <div>
                  <label class="block text-sm font-medium text-gray-700 mb-1">New Email Address</label>
                  <input
                    v-model="emailForm.email"
                    type="email"
                    required
                    :disabled="emailLoading"
                    class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
                  />
                  <p class="mt-1 text-sm text-gray-500">Your current email will be replaced. A verification email will be sent to the new address.</p>
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
      emailStatus: null,
      pendingEmails: [],
      passwordForm: {
        currentPassword: '',
        newPassword: '',
        confirmPassword: ''
      },
      emailForm: {
        email: ''
      },
      passwordLoading: false,
      emailLoading: false,
      resendLoading: false,
      resendCooldown: 0,
      cooldownInterval: null,
      passwordMessage: '',
      passwordMessageType: '',
      emailMessage: '',
      emailMessageType: ''
    }
  },
  methods: {
    async loadCurrentEmail() {
      try {
        const response = await axios.get('/api/user/email/status/', {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        });
        if (response.data.success) {
          this.currentEmail = response.data.primary_email || 'Not set';
          this.pendingEmails = response.data.pending_verification || [];
          
          // Find the primary email status
          if (response.data.emails && response.data.emails.length > 0) {
            const primaryEmail = response.data.emails.find(e => e.primary) || response.data.emails[0];
            this.emailStatus = {
              email: primaryEmail.email,
              verified: primaryEmail.verified,
              primary: primaryEmail.primary
            };
          }
          
          // Update cooldown status
          if (response.data.resend_on_cooldown && response.data.resend_cooldown_remaining) {
            this.resendCooldown = response.data.resend_cooldown_remaining;
            this.startCooldownTimer();
          } else {
            this.resendCooldown = 0;
            this.stopCooldownTimer();
          }
        }
      } catch (error) {
        console.error('Error loading email status:', error);
        this.currentEmail = 'Error loading email';
      }
    },
    async handlePasswordChange() {
      this.passwordLoading = true;
      this.passwordMessage = '';
      this.passwordMessageType = '';

      try {
        // Allauth ChangePasswordForm expects: oldpassword, password1, password2
        const response = await axios.post('/api/user/password/change/', {
          oldpassword: this.passwordForm.currentPassword,
          password1: this.passwordForm.newPassword,
          password2: this.passwordForm.confirmPassword
        }, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken'),
            'Content-Type': 'application/json'
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
        if (error.response && error.response.data) {
          if (error.response.data.error) {
            this.passwordMessage = error.response.data.error;
          } else if (error.response.data.errors) {
            // Handle multiple field errors
            const firstError = Object.values(error.response.data.errors)[0];
            this.passwordMessage = Array.isArray(firstError) ? firstError[0] : firstError;
          } else {
            this.passwordMessage = 'An error occurred while changing your password.';
          }
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
        // Allauth AddEmailForm expects: email
        const response = await axios.post('/api/user/email/change/', {
          email: this.emailForm.email
        }, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken'),
            'Content-Type': 'application/json'
          }
        });

        if (response.data.success) {
          this.emailMessage = response.data.message || 'Email address changed. Please check your email to verify it.';
          this.emailMessageType = 'success';
          // Clear form
          this.emailForm = {
            email: ''
          };
          // Reload email status to show updated email and verification status
          await this.loadCurrentEmail();
        } else {
          this.emailMessage = response.data.error || 'Failed to change email address.';
          this.emailMessageType = 'error';
        }
      } catch (error) {
        if (error.response && error.response.data) {
          if (error.response.data.error) {
            this.emailMessage = error.response.data.error;
          } else if (error.response.data.errors) {
            // Handle multiple field errors
            const firstError = Object.values(error.response.data.errors)[0];
            this.emailMessage = Array.isArray(firstError) ? firstError[0] : firstError;
          } else {
            this.emailMessage = 'An error occurred while changing your email address.';
          }
        } else {
          this.emailMessage = 'An error occurred while changing your email address.';
        }
        this.emailMessageType = 'error';
      } finally {
        this.emailLoading = false;
      }
    },
    async handleResendVerification() {
      if (!this.currentEmail || this.resendCooldown > 0) {
        return;
      }

      this.resendLoading = true;
      this.emailMessage = '';
      this.emailMessageType = '';

      try {
        const response = await axios.post('/api/user/email/resend-verification/', {
          email: this.currentEmail
        }, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken'),
            'Content-Type': 'application/json'
          }
        });

        if (response.data.success) {
          this.emailMessage = response.data.message || 'Verification email sent. Please check your inbox.';
          this.emailMessageType = 'success';
          
          // Start cooldown timer
          if (response.data.cooldown_remaining) {
            this.resendCooldown = response.data.cooldown_remaining;
            this.startCooldownTimer();
          }
        } else {
          this.emailMessage = response.data.error || 'Failed to send verification email.';
          this.emailMessageType = 'error';
        }
      } catch (error) {
        if (error.response && error.response.data) {
          // Handle cooldown error
          if (error.response.status === 429 && error.response.data.on_cooldown) {
            this.resendCooldown = error.response.data.cooldown_remaining || 60;
            this.startCooldownTimer();
            this.emailMessage = error.response.data.error || 'Please wait before requesting another verification email.';
          } else if (error.response.data.error) {
            this.emailMessage = error.response.data.error;
          } else {
            this.emailMessage = 'An error occurred while sending the verification email.';
          }
        } else {
          this.emailMessage = 'An error occurred while sending the verification email.';
        }
        this.emailMessageType = 'error';
      } finally {
        this.resendLoading = false;
      }
    },
    startCooldownTimer() {
      this.stopCooldownTimer(); // Clear any existing timer
      this.cooldownInterval = setInterval(() => {
        if (this.resendCooldown > 0) {
          this.resendCooldown--;
        } else {
          this.stopCooldownTimer();
        }
      }, 1000);
    },
    stopCooldownTimer() {
      if (this.cooldownInterval) {
        clearInterval(this.cooldownInterval);
        this.cooldownInterval = null;
      }
    }
  },
  async created() {
    await this.loadCurrentEmail();
  },
  beforeDestroy() {
    this.stopCooldownTimer();
  }
}
</script>

<style scoped>
</style>


