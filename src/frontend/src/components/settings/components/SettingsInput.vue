<template>
  <div class="space-y-4">
    <!-- Title label - only show for non-checkbox and non-toggle types -->
    <div v-if="setting.type !== 'checkbox' && setting.type !== 'toggle'" class="flex items-center gap-2">
      <label class="block text-sm font-medium text-gray-700">
        {{ setting.title }}
      </label>
      <Transition name="fade">
        <svg
          v-if="showSuccess"
          class="h-5 w-5 text-green-600"
          fill="currentColor"
          viewBox="0 0 20 20"
        >
          <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
        </svg>
      </Transition>
    </div>

    <!-- Radio button type -->
    <div v-if="setting.type === 'radio'" class="space-y-3">
      <div v-for="option in setting.options" :key="option.value" class="flex items-start">
        <input
          :id="`${setting.key}-${option.value}`"
          :value="option.value"
          :checked="modelValue === option.value"
          type="radio"
          @change="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
          class="radio-custom mt-1 h-4 w-4 text-blue-500 focus:ring-blue-500 border-gray-300"
        />
        <div class="ml-3">
          <label :for="`${setting.key}-${option.value}`" class="block text-sm font-medium text-gray-700 cursor-pointer">
            {{ option.label }}
          </label>
          <p v-if="option.description" class="text-sm text-gray-500 mt-1">
            {{ option.description }}
          </p>
        </div>
      </div>
    </div>

    <!-- Toggle type -->
    <div v-else-if="setting.type === 'toggle'" class="flex items-start gap-3">
      <div class="flex-1 min-w-0">
        <div class="flex items-center justify-between gap-3">
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2">
              <label 
                :for="setting.key" 
                class="block text-sm font-medium text-gray-700 cursor-pointer"
                @click="$emit('update:modelValue', !modelValue)"
              >
                {{ setting.label || setting.title }}
              </label>
              <Transition name="fade">
                <svg
                  v-if="showSuccess"
                  class="h-5 w-5 text-green-600 flex-shrink-0"
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
                </svg>
              </Transition>
            </div>
            <p v-if="setting.description" class="text-sm text-gray-500 mt-1">
              {{ setting.description }}
            </p>
          </div>
          <ToggleButton
            :id="setting.key"
            :model-value="booleanModelValue"
            :label="setting.label || setting.title"
            @update:model-value="$emit('update:modelValue', $event)"
          />
        </div>
      </div>
    </div>

    <!-- Checkbox type -->
    <div v-else-if="setting.type === 'checkbox'" class="flex items-start gap-3">
      <div class="flex-shrink-0 pt-0.5">
        <input
          type="checkbox"
          :id="setting.key"
          :checked="booleanModelValue"
          @change="$emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
          class="checkbox-custom"
        />
      </div>
      <div class="flex-1 min-w-0">
        <div class="flex items-center gap-2">
          <label :for="setting.key" class="block text-sm font-medium text-gray-700 cursor-pointer">
            {{ setting.label || setting.title }}
          </label>
          <Transition name="fade">
            <svg
              v-if="showSuccess"
              class="h-5 w-5 text-green-600 flex-shrink-0"
              fill="currentColor"
              viewBox="0 0 20 20"
            >
              <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
            </svg>
          </Transition>
        </div>
        <p v-if="setting.description" class="text-sm text-gray-500 mt-1">
          {{ setting.description }}
        </p>
      </div>
    </div>

    <!-- Select dropdown type -->
    <div v-else-if="setting.type === 'select'" class="space-y-2">
      <select
        :id="setting.key"
        :value="isLoading ? '' : stringModelValue"
        @change="$emit('update:modelValue', ($event.target as HTMLSelectElement).value)"
        :disabled="isLoading"
        class="select-custom w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none disabled:bg-gray-100 disabled:cursor-not-allowed"
      >
        <option v-if="isLoading" value="" selected>
          Loading...
        </option>
        <template v-else>
          <option v-for="option in setting.options" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </template>
      </select>
      <p v-if="setting.description" class="text-sm text-gray-500">
        {{ setting.description }}
      </p>
    </div>

    <!-- Text input type -->
    <div v-else-if="setting.type === 'text'" class="space-y-2">
      <input
        :id="setting.key"
        :value="stringModelValue"
        type="text"
        :placeholder="setting.placeholder"
        @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
        class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
      />
      <p v-if="setting.description" class="text-sm text-gray-500">
        {{ setting.description }}
      </p>
    </div>

    <!-- Number input type -->
    <div v-else-if="setting.type === 'number'" class="space-y-2">
      <input
        :id="setting.key"
        :value="stringModelValue"
        type="number"
        :min="setting.min"
        :max="setting.max"
        :step="setting.step"
        :placeholder="setting.placeholder"
        @input="$emit('update:modelValue', parseFloat(($event.target as HTMLInputElement).value) || 0)"
        class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
      />
      <p v-if="setting.description" class="text-sm text-gray-500">
        {{ setting.description }}
      </p>
    </div>

    <!-- Textarea type -->
    <div v-else-if="setting.type === 'textarea'" class="space-y-2">
      <textarea
        :id="setting.key"
        :value="stringModelValue"
        :rows="setting.rows || 4"
        :placeholder="setting.placeholder"
        @input="$emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
        class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
      ></textarea>
      <p v-if="setting.description" class="text-sm text-gray-500">
        {{ setting.description }}
      </p>
    </div>

    <!-- Unsupported type warning -->
    <div v-else class="p-3 bg-yellow-50 border border-yellow-200 rounded-md">
      <p class="text-sm text-yellow-800">
        Unsupported setting type: {{ setting.type }}
      </p>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import { mapGetters } from 'vuex'
import ToggleButton from '@/components/parts/ToggleButton.vue'
import type { SettingDefinition, SettingValue } from '@/composables/useSettingsSection'

export default defineComponent({
  name: 'SettingsInput',
  components: {
    ToggleButton
  },
  props: {
    setting: {
      type: Object as PropType<SettingDefinition>,
      required: true,
      validator(value: SettingDefinition): boolean {
        return Boolean(value.key && value.title);
      }
    },
    modelValue: {
      type: [String, Number, Boolean] as PropType<SettingValue | null>,
      default: null
    },
    showSuccess: {
      type: Boolean,
      default: false
    }
  },
  emits: ['update:modelValue'],
  computed: {
    // `userSettings/userSettings` is null until the store's initial fetch resolves.
    ...mapGetters('userSettings', ['userSettings']),
    // Narrowed views of `modelValue` for the DOM attribute bindings below, which each only
    // ever receive one specific type depending on `setting.type`.
    booleanModelValue(): boolean {
      return Boolean(this.modelValue);
    },
    stringModelValue(): string {
      return this.modelValue == null ? '' : String(this.modelValue);
    },
    isLoading(): boolean {
      const settingsLoading: boolean = this.userSettings === null;

      // For select dropdowns, also check if options are empty (e.g., default_basemap)
      // Options might be populated asynchronously after component creation
      const optionsEmpty = this.setting.type === 'select' &&
        (!this.setting.options || this.setting.options.length === 0);

      return settingsLoading || optionsEmpty;
    }
  }
})
</script>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* Radio button styles are now in main.css */
</style>

