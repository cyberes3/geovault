<template>
  <div class="space-y-6">
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
          class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
          title="Change password"
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
            class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
            title="Change email address"
          >
            <span v-if="emailLoading">Changing...</span>
            <span v-else>Change Email</span>
          </button>
        </form>
      </div>
    </div>

    <!-- Data Export Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Data Export</h2>
      <p class="text-sm text-gray-600 mb-4">
        Download all your features and associated data as a single KMZ file. This file can be opened in Google Earth or other GIS software.
      </p>
      
      <div v-if="downloadMessage" :class="[
        'p-3 rounded-md text-sm mb-4',
        downloadMessageType === 'success' ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'
      ]">
        {{ downloadMessage }}
      </div>

      <button
        @click="handleDownloadFeatures"
        :disabled="downloadLoading"
        class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
        title="Download all features as KMZ"
      >
        <span v-if="downloadLoading">Preparing Download...</span>
        <span v-else>Download All Features (KMZ)</span>
      </button>
    </div>
  </div>
</template>

<script>
import axios from "axios";
import { getCookie } from "@/assets/js/auth.js";

export default {
  name: 'AccountSettingsTab',
  props: {
    toastRef: {
      type: Object,
      default: null
    }
  },
  data() {
    return {
      dataLoaded: false,
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
      downloadLoading: false,
      resendCooldown: 0,
      cooldownInterval: null,
      passwordMessage: '',
      passwordMessageType: '',
      emailMessage: '',
      emailMessageType: '',
      downloadMessage: '',
      downloadMessageType: ''
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
        if (response.status === 200) {
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

        if (response.status === 200) {
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

        if (response.status === 200) {
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

        if (response.status === 200) {
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
    },
    async handleDownloadFeatures() {
      this.downloadLoading = true;
      this.downloadMessage = '';
      this.downloadMessageType = '';

      try {
        const response = await axios.get('/api/export-kmz?all=true', {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          },
          responseType: 'blob' // Important for file downloads
        });

        if (response.status === 200) {
          // Create a blob link to download the file
          const url = window.URL.createObjectURL(new Blob([response.data]));
          const link = document.createElement('a');
          link.href = url;
          
          // Extract filename from Content-Disposition header if available
          let filename = 'all-features.kmz';
          const contentDisposition = response.headers['content-disposition'];
          if (contentDisposition) {
            const filenameMatch = contentDisposition.match(/filename="?([^"]+)"?/);
            if (filenameMatch && filenameMatch.length === 2) {
              filename = filenameMatch[1];
            }
          }
          
          link.setAttribute('download', filename);
          document.body.appendChild(link);
          link.click();
          
          // Cleanup
          document.body.removeChild(link);
          window.URL.revokeObjectURL(url);
          
          // Only show error messages, success is obvious by the download starting
          this.downloadMessage = '';
          this.downloadMessageType = '';
        } else {
          this.downloadMessage = 'Failed to download features.';
          this.downloadMessageType = 'error';
        }
      } catch (error) {
        console.error('Error downloading features:', error);
        if (error.response && error.response.data) {
           // Try to parse JSON error from blob
           if (error.response.data instanceof Blob && error.response.data.type === 'application/json') {
              try {
                const text = await error.response.data.text();
                const json = JSON.parse(text);
                this.downloadMessage = json.error || 'An error occurred during download.';
              } catch (e) {
                this.downloadMessage = 'An error occurred during download.';
              }
           } else {
             this.downloadMessage = error.response.data.error || 'An error occurred during download.';
           }
        } else {
          this.downloadMessage = 'An error occurred during download.';
        }
        this.downloadMessageType = 'error';
      } finally {
        this.downloadLoading = false;
      }
    }
  },
  async created() {
    if (!this.dataLoaded) {
      await this.loadCurrentEmail();
      this.dataLoaded = true;
    }
  },
  beforeDestroy() {
    this.stopCooldownTimer();
  }
}
</script>

