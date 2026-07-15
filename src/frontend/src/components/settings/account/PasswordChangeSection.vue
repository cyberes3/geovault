<template>
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
      <div v-if="passwordMessage" class="p-3 rounded-md text-sm bg-green-50 text-green-800">
        {{ passwordMessage }}
      </div>
      <BaseButton
          type="submit"
          :disabled="passwordLoading"
          variant="primary"
          color="blue"
          size="sm"
          title="Change Password"
      >
        <span v-if="passwordLoading">Changing...</span>
        <span v-else>Change Password</span>
      </BaseButton>
    </form>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import BaseButton from '@/components/parts/BaseButton.vue';
import { changePassword } from '@/api/services/userApi';
import { toastApiError } from '@/utils/apiError';

const passwordForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
});
const passwordLoading = ref(false);
const passwordMessage = ref('');

async function handlePasswordChange(): Promise<void> {
  passwordLoading.value = true;
  passwordMessage.value = '';

  try {
    const data = await changePassword(
        passwordForm.currentPassword,
        passwordForm.newPassword,
        passwordForm.confirmPassword,
    );
    passwordMessage.value = data.message || 'Password changed successfully.';
    passwordForm.currentPassword = '';
    passwordForm.newPassword = '';
    passwordForm.confirmPassword = '';
  } catch (error) {
    toastApiError(error, 'An error occurred while changing your password.');
  } finally {
    passwordLoading.value = false;
  }
}
</script>
