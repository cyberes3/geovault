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
        <BaseButton
            type="submit"
            :disabled="passwordLoading"
            variant="primary"
            color="blue"
            size="sm"
            title="Change password"
        >
          <span v-if="passwordLoading">Changing...</span>
          <span v-else>Change Password</span>
        </BaseButton>
      </form>
    </div>

    <!-- Email Address Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Email Address</h2>

      <!-- Current Email Status -->
      <div class="mb-6 p-4 bg-gray-50 rounded-md">
        <div class="flex items-center justify-between">
          <div>
            <p class="text-sm font-medium text-gray-700">Current Email</p>
            <div v-if="emailStatusLoading" class="mt-1 flex items-center gap-2 min-h-[1.5rem]">
              <Loader size="sm" layout="inline" message="Loading email status..." :showMessage="true"/>
            </div>
            <template v-else>
              <div class="mt-1 flex items-center gap-2 min-h-[1.5rem]">
                <p class="text-sm text-gray-900">{{ currentEmail || 'Not set' }}</p>
                <span v-if="emailStatus && emailStatus.verified"
                      class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800">
                  Verified
                </span>
                <span v-else-if="emailStatus && !emailStatus.verified"
                      class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-yellow-100 text-yellow-800">
                  Unverified
                </span>
              </div>
            </template>
          </div>
          <div class="relative group" v-if="!emailStatusLoading && emailStatus && !emailStatus.verified">
            <BaseButton
                @click="handleResendVerification"
                :disabled="resendLoading || resendCooldown > 0"
                :title="resendCooldown > 0 ? `Please wait ${resendCooldown} second${resendCooldown !== 1 ? 's' : ''} before resending` : 'Resend verification email'"
                variant="white"
                size="sm"
            >
              <span v-if="resendLoading">Sending...</span>
              <span v-else-if="resendCooldown > 0">Resend ({{ resendCooldown }}s)</span>
              <span v-else>Resend Verification</span>
            </BaseButton>
            <div
                v-if="resendCooldown > 0"
                class="absolute z-10 px-2 py-1 text-xs text-white bg-gray-900 rounded shadow-lg bottom-full mb-2 left-1/2 transform -translate-x-1/2 whitespace-nowrap pointer-events-none opacity-0 group-hover:opacity-100 transition-opacity duration-200"
            >
              Please wait {{ resendCooldown }} second{{ resendCooldown !== 1 ? 's' : '' }} before resending
              <div class="absolute top-full left-1/2 transform -translate-x-1/2 -mt-1">
                <div class="border-4 border-transparent border-t-gray-900"></div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Unverified Email Notice or Resend Success Message -->
      <div v-if="emailStatus && !emailStatus.verified" class="mb-4 p-4 rounded-md" :class="[
        resendMessageType === 'success' 
          ? 'bg-green-50 border border-green-200' 
          : 'bg-yellow-50 border border-yellow-200'
      ]">
        <p v-if="resendMessageType === 'success'" class="text-sm text-green-800">
          {{ resendMessage }}
        </p>
        <p v-else class="text-sm text-yellow-800">
          <strong>Email Verification Required:</strong> Your email address is not yet verified. Please check your inbox
          and click the verification link to complete the process.
        </p>
      </div>
    </div>

    <!-- Data Export Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Data Export</h2>
      <p class="text-sm text-gray-600 mb-4">
        Download all your features and associated data as a single KMZ file. This file can be opened in Google Earth or
        other GIS software.
      </p>

      <div v-if="downloadMessage" :class="[
        'p-3 rounded-md text-sm mb-4',
        downloadMessageType === 'success' ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'
      ]">
        {{ downloadMessage }}
      </div>

      <BaseButton
          @click="handleDownloadFeatures"
          :disabled="downloadLoading"
          variant="primary"
          color="blue"
          size="sm"
          title="Download all features as KMZ"
      >
        <span v-if="downloadLoading">Preparing Download...</span>
        <span v-else>Download All Features (KMZ)</span>
      </BaseButton>
    </div>

    <!-- Account Settings Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Account Preferences</h2>

      <!-- Dynamically generated settings -->
      <div class="space-y-6">
        <SettingsInput
            v-for="setting in getSettingsForSection('account')"
            :key="setting.key"
            :setting="setting"
            :model-value="settingsValues[setting.key]"
            :show-success="successCheckmarks[setting.key]"
            @update:model-value="handleSettingChange(setting.key, $event)"
        />
      </div>
    </div>

    <!-- API Keys Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">API Keys</h2>
      <p class="text-sm text-gray-600 mb-4">
        Create API keys to programmatically connect external services to your account. Keys can be used to upload files
        and access your data.
      </p>

      <!-- Create New API Key Form -->
      <div class="mb-6 pb-6 border-b border-gray-200">
        <h3 class="text-md font-medium text-gray-900 mb-3">Create New API Key</h3>
        <form @submit.prevent="handleCreateApiKey" class="space-y-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Key Name (optional)</label>
            <input
                v-model="newKeyName"
                type="text"
                :disabled="createKeyLoading"
                placeholder="e.g., My Phone, Desktop App (optional)"
                class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
            />
          </div>
          <div v-if="apiKeyMessage" :class="[
            'p-3 rounded-md text-sm',
            apiKeyMessageType === 'success' ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'
          ]">
            {{ apiKeyMessage }}
          </div>
          <BaseButton
              type="submit"
              :disabled="createKeyLoading"
              variant="primary"
              color="blue"
              size="sm"
              title="Create new API key"
          >
            <span v-if="createKeyLoading">Creating...</span>
            <span v-else>Create API Key</span>
          </BaseButton>
        </form>

        <!-- Display raw key after creation (shown only once) -->
        <div v-if="newKeyRawValue" class="mt-4 p-4 bg-gray-50 border border-gray-200 rounded-md shadow-sm">
          <p class="text-sm font-medium text-gray-900 mb-2">
            Please copy this key now - you'll need it to use the API. For your security, it will only be shown once.
          </p>
          <div class="flex items-center gap-2">
            <input
                :value="newKeyRawValue"
                type="text"
                readonly
                class="flex-1 px-3 py-2 bg-white border border-gray-300 rounded-md text-sm font-mono text-gray-700"
                ref="newKeyInput"
                @focus="$event.target.select()"
            />
            <BaseButton
                @click="copyApiKey"
                variant="primary"
                color="blue"
                size="sm"
            >
              <span v-if="copyButtonShowingIcon" class="inline-flex items-center justify-center w-12 h-5">
                <ClipboardDocumentIcon class="w-4 h-4"/>
              </span>
              <span v-else class="inline-block w-12 h-5 text-center leading-5">Copy</span>
            </BaseButton>
          </div>
        </div>
      </div>

      <!-- Existing API Keys List -->
      <div>
        <h3 class="text-md font-medium text-gray-900 mb-3">Your API Keys</h3>
        <div v-if="apiKeysLoading" class="flex items-center justify-center py-12 min-h-[120px]">
          <Loader size="sm" layout="inline" message="Loading API keys..." :showMessage="true" />
        </div>
        <div v-else-if="apiKeys.length === 0" class="py-8 px-4 text-center text-sm text-gray-500 bg-gray-50 border border-gray-200 rounded-md">
          No API keys created yet.
        </div>
        <div v-else class="space-y-3">
          <div
              v-for="key in apiKeys"
              :key="key.id"
              class="p-4 bg-gray-50 border border-gray-200 rounded-md hover:bg-gray-100 transition-colors"
          >
            <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2 mb-1">
                  <span class="text-sm font-medium text-gray-900">{{ key.name }}</span>
                </div>
                <div class="text-xs text-gray-500 space-y-1">
                  <div>Key: <span class="font-mono">{{ key.key_prefix }}...</span></div>
                  <div>Created: {{ formatDate(key.created_at) }}</div>
                  <div v-if="key.last_used_at">
                    Last used: {{ formatDate(key.last_used_at) }}
                  </div>
                  <div v-else class="text-gray-400">Never used</div>
                </div>
              </div>
              <BaseButton
                  @click="handleDeleteApiKey(key.id)"
                  :disabled="deleteKeyLoading === key.id"
                  variant="secondary"
                  color="red"
                  size="sm"
                  title="Delete this API key"
              >
                <span v-if="deleteKeyLoading === key.id">Deleting...</span>
                <span v-else>Delete</span>
              </BaseButton>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Authorized OAuth Applications Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Authorized OAuth Applications</h2>
      <p class="text-sm text-gray-600 mb-4">
        Applications that you have signed in to with OAuth. Revoking removes their access. To create or manage OAuth
        applications (for development or third-party integrations), use the link below.
      </p>
      <p class="mb-4">
        <a
          :href="oauthApplicationsUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="text-blue-600 hover:text-blue-800 underline"
        >
          Manage your OAuth applications
        </a>
      </p>

      <div v-if="oauthTokensLoading" class="flex items-center justify-center py-12 min-h-[120px]">
        <Loader size="sm" layout="inline" message="Loading authorized applications..." :showMessage="true" />
      </div>
      <div v-else-if="oauthTokens.length === 0" class="py-8 px-4 text-center text-sm text-gray-500 bg-gray-50 border border-gray-200 rounded-md">
        No authorized OAuth applications.
      </div>
      <div v-else class="space-y-3">
        <div
          v-for="token in oauthTokens"
          :key="token.id"
          class="p-4 bg-gray-50 border border-gray-200 rounded-md hover:bg-gray-100 transition-colors"
        >
          <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-1">
                <span class="text-sm font-medium text-gray-900">{{ token.application_name }}</span>
              </div>
              <div class="text-xs text-gray-500 space-y-1">
                <div>Authorized: {{ formatDate(token.created) }}</div>
                <div>Expires: {{ formatDate(token.expires) }}</div>
              </div>
            </div>
            <BaseButton
              @click="handleRevokeOAuthToken(token.id)"
              :disabled="revokeOAuthLoading === token.id"
              variant="secondary"
              color="red"
              size="sm"
              title="Revoke access for this application"
            >
              <span v-if="revokeOAuthLoading === token.id">Revoking...</span>
              <span v-else>Revoke</span>
            </BaseButton>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import axios from "axios";
import {getCookie} from "@/assets/js/auth.js";
import settingsConfig from "@/components/settings-data.json";
import SettingsMixin from "./mixins/SettingsMixin.js";
import SettingsInput from "./components/SettingsInput.vue";
import Loader from "@/components/parts/Loader.vue";
import BaseButton from "@/components/parts/BaseButton.vue";
import {formatDate} from "@/utils/dateUtils.js";
import {ClipboardDocumentIcon} from '@heroicons/vue/24/outline';

export default {
  name: 'AccountSettingsTab',
  components: {
    SettingsInput,
    Loader,
    BaseButton,
    ClipboardDocumentIcon
  },
  mixins: [SettingsMixin],
  props: {},
  data() {
    return {
      // Settings configuration - loaded from external JSON file
      settingsConfig: settingsConfig,
      dataLoaded: false,
      currentEmail: '',
      emailStatus: null,
      emailStatusLoading: false,
      pendingEmails: [],
      passwordForm: {
        currentPassword: '',
        newPassword: '',
        confirmPassword: ''
      },
      passwordLoading: false,
      resendLoading: false,
      downloadLoading: false,
      resendCooldown: 0,
      cooldownInterval: null,
      passwordMessage: '',
      passwordMessageType: '',
      resendMessage: '',
      resendMessageType: '',
      downloadMessage: '',
      downloadMessageType: '',
      // API Keys state
      apiKeys: [],
      apiKeysLoading: false,
      newKeyName: '',
      newKeyRawValue: '',
      createKeyLoading: false,
      deleteKeyLoading: null,
      apiKeyMessage: '',
      apiKeyMessageType: '',
      copyButtonShowingIcon: false,
      // OAuth authorized applications
      oauthTokens: [],
      oauthTokensLoading: false,
      revokeOAuthLoading: null
    }
  },
  computed: {
    oauthApplicationsUrl() {
      return `${window.location.origin}/api/oauth/applications/`;
    }
  },
  methods: {
    async loadCurrentEmail() {
      this.emailStatusLoading = true;
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
      } finally {
        this.emailStatusLoading = false;
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
    async handleResendVerification() {
      if (!this.currentEmail || this.resendCooldown > 0) {
        return;
      }

      this.resendLoading = true;
      this.resendMessage = '';
      this.resendMessageType = '';

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
          this.resendMessage = response.data.message || 'Verification email sent. Please check your inbox.';
          this.resendMessageType = 'success';

          // Start cooldown timer
          if (response.data.cooldown_remaining) {
            this.resendCooldown = response.data.cooldown_remaining;
            this.startCooldownTimer();
          }
        } else {
          this.resendMessage = response.data.error || 'Failed to send verification email.';
          this.resendMessageType = 'error';
        }
      } catch (error) {
        if (error.response && error.response.data) {
          // Handle cooldown error
          if (error.response.status === 429 && error.response.data.on_cooldown) {
            this.resendCooldown = error.response.data.cooldown_remaining || 60;
            this.startCooldownTimer();
            this.resendMessage = error.response.data.error || 'Please wait before requesting another verification email.';
          } else if (error.response.data.error) {
            this.resendMessage = error.response.data.error;
          } else {
            this.resendMessage = 'An error occurred while sending the verification email.';
          }
        } else {
          this.resendMessage = 'An error occurred while sending the verification email.';
        }
        this.resendMessageType = 'error';
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
    },
    async loadApiKeys() {
      this.apiKeysLoading = true;
      try {
        const response = await axios.get('/api/user/api-keys/', {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        });
        if (response.status === 200) {
          this.apiKeys = response.data.api_keys || [];
        }
      } catch (error) {
        console.error('Error loading API keys:', error);
        this.apiKeys = [];
      } finally {
        this.apiKeysLoading = false;
      }
    },
    async handleCreateApiKey() {
      this.createKeyLoading = true;
      this.apiKeyMessage = '';
      this.apiKeyMessageType = '';
      this.newKeyRawValue = '';

      try {
        const response = await axios.post('/api/user/api-keys/create/', {
          name: this.newKeyName.trim()
        }, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken'),
            'Content-Type': 'application/json'
          }
        });

        if (response.status === 201) {
          this.newKeyRawValue = response.data.raw_key;
          this.newKeyName = '';
          // Reload the list
          await this.loadApiKeys();
          // Scroll to the new key display
          this.$nextTick(() => {
            if (this.$refs.newKeyInput) {
              this.$refs.newKeyInput.scrollIntoView({behavior: 'smooth', block: 'nearest'});
            }
          });
        } else {
          this.apiKeyMessage = response.data.error || 'Failed to create API key';
          this.apiKeyMessageType = 'error';
        }
      } catch (error) {
        if (error.response && error.response.data) {
          this.apiKeyMessage = error.response.data.error || 'An error occurred while creating the API key';
        } else {
          this.apiKeyMessage = 'An error occurred while creating the API key';
        }
        this.apiKeyMessageType = 'error';
      } finally {
        this.createKeyLoading = false;
      }
    },
    async handleDeleteApiKey(keyId) {
      if (!confirm('Are you sure you want to delete this API key? You can always create a new one if needed.')) {
        return;
      }

      this.deleteKeyLoading = keyId;
      try {
        const response = await axios.delete(`/api/user/api-keys/${keyId}/`, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        });

        if (response.status === 200) {
          // Reload the list
          await this.loadApiKeys();
          this.apiKeyMessage = 'API key deleted successfully';
          this.apiKeyMessageType = 'success';
          // Clear message after a few seconds
          setTimeout(() => {
            this.apiKeyMessage = '';
            this.apiKeyMessageType = '';
          }, 3000);
        } else {
          this.apiKeyMessage = response.data.error || 'Failed to delete API key';
          this.apiKeyMessageType = 'error';
        }
      } catch (error) {
        if (error.response && error.response.data) {
          this.apiKeyMessage = error.response.data.error || 'An error occurred while deleting the API key';
        } else {
          this.apiKeyMessage = 'An error occurred while deleting the API key';
        }
        this.apiKeyMessageType = 'error';
      } finally {
        this.deleteKeyLoading = null;
      }
    },
    async copyApiKey() {
      if (!this.newKeyRawValue) return;

      // Try modern clipboard API first
      if (navigator.clipboard && navigator.clipboard.writeText) {
        try {
          await navigator.clipboard.writeText(this.newKeyRawValue);
          this.showCopySuccess();
          return;
        } catch (err) {
          console.warn('Clipboard API failed, trying fallback:', err);
        }
      }

      // Fallback for mobile and older browsers
      const input = this.$refs.newKeyInput;
      if (input) {
        try {
          // Select the text
          input.select();
          input.setSelectionRange(0, 99999); // For mobile devices

          // Try execCommand as fallback
          const successful = document.execCommand('copy');
          if (successful) {
            this.showCopySuccess();
          } else {
            // If execCommand fails, at least the text is selected so user can manually copy
            this.apiKeyMessage = 'Text selected - please copy manually (Ctrl+C or Cmd+C)';
            this.apiKeyMessageType = 'error';
            setTimeout(() => {
              this.apiKeyMessage = '';
              this.apiKeyMessageType = '';
            }, 3000);
          }
        } catch (err) {
          console.error('Failed to copy:', err);
          this.apiKeyMessage = 'Failed to copy. Please select and copy manually.';
          this.apiKeyMessageType = 'error';
          setTimeout(() => {
            this.apiKeyMessage = '';
            this.apiKeyMessageType = '';
          }, 3000);
        }
      }
    },
    showCopySuccess() {
      this.copyButtonShowingIcon = true;
      setTimeout(() => {
        this.copyButtonShowingIcon = false;
      }, 1000);
    },
    async loadOAuthTokens() {
      this.oauthTokensLoading = true;
      try {
        const response = await axios.get('/api/user/oauth-authorized-tokens/', {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        });
        if (response.status === 200) {
          this.oauthTokens = response.data.authorized_tokens || [];
        }
      } catch (error) {
        console.error('Error loading OAuth tokens:', error);
        this.oauthTokens = [];
      } finally {
        this.oauthTokensLoading = false;
      }
    },
    async handleRevokeOAuthToken(tokenId) {
      if (!confirm('Revoke access for this application? It will need to be authorized again to access your account.')) {
        return;
      }
      this.revokeOAuthLoading = tokenId;
      try {
        const response = await axios.delete(`/api/user/oauth-authorized-tokens/${tokenId}/`, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        });
        if (response.status >= 200 && response.status < 300) {
          this.oauthTokens = this.oauthTokens.filter((t) => t.id !== tokenId);
          await this.loadOAuthTokens();
        } else {
          this.apiKeyMessage = response.data?.error || 'Failed to revoke';
          this.apiKeyMessageType = 'error';
          setTimeout(() => {
            this.apiKeyMessage = '';
            this.apiKeyMessageType = '';
          }, 3000);
        }
      } catch (error) {
        if (error.response && error.response.data) {
          this.apiKeyMessage = error.response.data.error || 'Failed to revoke authorization';
        } else {
          this.apiKeyMessage = 'Failed to revoke authorization';
        }
        this.apiKeyMessageType = 'error';
        setTimeout(() => {
          this.apiKeyMessage = '';
          this.apiKeyMessageType = '';
        }, 3000);
      } finally {
        this.revokeOAuthLoading = null;
      }
    },
    formatDate
  },
  async created() {
    if (!this.dataLoaded) {
      await this.loadCurrentEmail();
      this.dataLoaded = true;
    }
    // Load settings from store using mixin method
    this.loadSettingsFromStore();
    // Load API keys and OAuth authorized tokens
    await this.loadApiKeys();
    await this.loadOAuthTokens();
  },
  watch: {
    // Watch for changes in the store and reload settings
    '$store.state.userSettings': {
      handler() {
        // Reload settings when store updates
        this.loadSettingsFromStore();
      },
      deep: true
    }
  },
  beforeDestroy() {
    this.stopCooldownTimer();
  }
}
</script>

