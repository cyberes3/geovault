<template>
  <div class="space-y-4">
    <!-- Name Field -->
    <div>
      <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Name</label>
      <input
        :value="name"
        @input="onNameInput"
        type="text"
        :disabled="disabled"
        class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
        required
      />
    </div>

    <!-- Description Field -->
    <div>
      <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Description</label>
      <textarea
        :value="description"
        @input="onDescriptionInput"
        rows="3"
        :disabled="disabled"
        class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
      ></textarea>
    </div>

    <!-- Created Date Field -->
    <div>
      <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Created Date</label>
      <input
        type="datetime-local"
        :disabled="disabled"
        :value="createdDateForInput"
        @change="onCreatedChange"
        class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  name: string;
  description: string;
  createdDateForInput: string;
  disabled: boolean;
}>();

const emit = defineEmits<{
  'update:name': [value: string];
  'update:description': [value: string];
  'update:created': [value: string];
}>();

function onNameInput(event: Event) {
  emit('update:name', (event.target as HTMLInputElement).value);
}

function onDescriptionInput(event: Event) {
  emit('update:description', (event.target as HTMLTextAreaElement).value);
}

function onCreatedChange(event: Event) {
  emit('update:created', (event.target as HTMLInputElement).value);
}
</script>
