<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-4">Email Address</h2>

    <!-- Current Email Status -->
    <div class="mb-6 p-4 bg-gray-50 rounded-md">
      <div class="flex items-center justify-between">
        <div>
          <p class="text-sm font-medium text-gray-700">Current Email</p>
          <div v-if="emailStatusLoading" class="mt-1 flex items-center gap-2 min-h-[1.5rem]">
            <Loader size="sm" layout="inline" message="Loading email status..." :show-message="true"/>
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
      resendMessage ? 'bg-green-50 border border-green-200' : 'bg-yellow-50 border border-yellow-200'
    ]">
      <p v-if="resendMessage" class="text-sm text-green-800">
        {{ resendMessage }}
      </p>
      <p v-else class="text-sm text-yellow-800">
        <strong>Email Verification Required:</strong> Your email address is not yet verified. Please check your inbox
        and click the verification link to complete the process.
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import BaseButton from '@/components/parts/BaseButton.vue';
import Loader from '@/components/parts/Loader.vue';
import { getEmailStatus, resendEmailVerification } from '@/api/services/userApi';
import { ApiError, toastApiError } from '@/utils/apiError';

interface EmailStatus {
  email: string;
  verified: boolean;
  primary: boolean;
}

const currentEmail = ref('');
const emailStatus = ref<EmailStatus | null>(null);
const emailStatusLoading = ref(false);
const resendLoading = ref(false);
const resendMessage = ref('');
const resendCooldown = ref(0);
let cooldownInterval: ReturnType<typeof setInterval> | null = null;

function startCooldownTimer(): void {
  stopCooldownTimer();
  cooldownInterval = setInterval(() => {
    if (resendCooldown.value > 0) {
      resendCooldown.value--;
    } else {
      stopCooldownTimer();
    }
  }, 1000);
}

function stopCooldownTimer(): void {
  if (cooldownInterval) {
    clearInterval(cooldownInterval);
    cooldownInterval = null;
  }
}

async function loadCurrentEmail(): Promise<void> {
  emailStatusLoading.value = true;
  try {
    const data = await getEmailStatus();
    currentEmail.value = data.primary_email || 'Not set';

    if (data.emails.length > 0) {
      const primaryEmail = data.emails.find((e) => e.primary) ?? data.emails[0];
      emailStatus.value = {
        email: primaryEmail.email,
        verified: primaryEmail.verified,
        primary: primaryEmail.primary,
      };
    }

    if (data.resend_on_cooldown && data.resend_cooldown_remaining) {
      resendCooldown.value = data.resend_cooldown_remaining;
      startCooldownTimer();
    } else {
      resendCooldown.value = 0;
      stopCooldownTimer();
    }
  } catch (error) {
    toastApiError(error, 'Error loading email status');
    currentEmail.value = 'Error loading email';
  } finally {
    emailStatusLoading.value = false;
  }
}

async function handleResendVerification(): Promise<void> {
  if (!currentEmail.value || resendCooldown.value > 0) {
    return;
  }

  resendLoading.value = true;
  resendMessage.value = '';

  try {
    const data = await resendEmailVerification(currentEmail.value);
    resendMessage.value = data.message || 'Verification email sent. Please check your inbox.';

    if (data.cooldown_remaining) {
      resendCooldown.value = data.cooldown_remaining;
      startCooldownTimer();
    }
  } catch (error) {
    const apiError = ApiError.from(error);
    if (apiError.status === 429 && apiError.data && typeof apiError.data === 'object' && 'cooldown_remaining' in apiError.data) {
      resendCooldown.value = Number((apiError.data as { cooldown_remaining?: unknown }).cooldown_remaining) || 60;
      startCooldownTimer();
    }
    toastApiError(apiError, 'An error occurred while sending the verification email.');
  } finally {
    resendLoading.value = false;
  }
}

onMounted(() => {
  void loadCurrentEmail();
});

onBeforeUnmount(() => {
  stopCooldownTimer();
});
</script>
