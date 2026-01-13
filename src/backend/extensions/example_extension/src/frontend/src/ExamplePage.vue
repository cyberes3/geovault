<template>
  <!-- 
    A full-page extension component. 
    Standard container with shadow and border to match the platform aesthetic.
  -->
  <div class="p-6 bg-white rounded-lg shadow-sm border border-gray-200 example-custom-card">
    <h2 class="text-2xl font-bold mb-6 text-gray-900">Example Extension CRUD</h2>
    
    <!-- Input Section -->
    <div class="mb-8 flex gap-3">
      <input 
        v-model="newItem.name" 
        placeholder="Enter item name..." 
        class="border border-gray-300 px-4 py-2 rounded-lg flex-1 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all shadow-sm" 
        @keyup.enter="addItem" 
      />
      <!-- Loader is a globally registered platform component -->
      <BaseButton @click="addItem" :disabled="!newItem.name.trim() || adding">
        <Loader v-if="adding" size="sm" layout="inline" :showMessage="false" color="white" class="mr-2" />
        Add Item
      </BaseButton>
    </div>

    <!-- Loading State -->
    <div v-if="loading" class="py-12 flex justify-center">
      <Loader size="md" message="Fetching items..." />
    </div>

    <!-- Item List -->
    <div v-else-if="items.length > 0" class="space-y-4">
      <TransitionGroup name="list">
        <div v-for="item in items" :key="item.id" class="flex justify-between items-center p-5 border border-gray-100 rounded-xl hover:bg-gray-50 transition-all shadow-sm bg-white">
          <div class="flex-1 min-w-0">
            <span class="font-bold text-gray-900 text-lg block truncate">{{ item.name }}</span>
            <p class="text-sm text-gray-500 mt-1 line-clamp-1">{{ item.description || 'No description provided' }}</p>
          </div>
          <div class="ml-4 flex-shrink-0">
            <BaseButton variant="secondary" color="red" size="sm" @click="deleteItem(item.id)" title="Delete item">
               <!-- Standard heroicon-style SVG -->
               <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                 <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
               </svg>
            </BaseButton>
          </div>
        </div>
      </TransitionGroup>
    </div>

    <!-- Empty State -->
    <div v-else class="text-center py-20 bg-gray-50 rounded-xl border-2 border-dashed border-gray-200">
      <div class="mx-auto w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mb-4">
        <svg class="w-8 h-8 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4"></path>
        </svg>
      </div>
      <h3 class="text-lg font-medium text-gray-900">No items found</h3>
      <p class="text-gray-500 mt-1">Add your first item using the form above.</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, inject } from 'vue';

/*
  API INJECTION
  The 'api' object is injected from main.js. It automatically prefixes
  your requests with /api/extensions/<name>/ so you don't have to hardcode URLs.
*/
const api = inject('exampleExtensionApi');

/*
  TOAST UTILITY
  Access the platform's native notification system.
*/
const toast = window.GeoVault.toast;

// Local component state
const items = ref([]);
const newItem = ref({ name: '', description: '' });
const loading = ref(true);
const adding = ref(false);

/**
 * Fetch items from the extension's backend.
 */
const fetchItems = async () => {
  loading.value = true;
  try {
    // api.url('/items/') returns /api/extensions/example_extension/items/
    const res = await fetch(api.url('/items/'));
    if (!res.ok) throw new Error('Failed to fetch');
    items.value = await res.json();
  } catch (e) {
    console.error('Failed to fetch items:', e);
    if (toast) toast.error('Could not load items');
  } finally {
    loading.value = false;
  }
};

/**
 * Create a new item.
 */
const addItem = async () => {
  const name = newItem.value.name.trim();
  if (!name || adding.value) return;
  
  adding.value = true;
  try {
    const res = await fetch(api.url('/items/'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, description: '' })
    });
    
    if (res.ok) {
      newItem.value.name = ''; // Clear input
      await fetchItems();      // Refresh list
      if (toast) toast.success('Item added successfully');
    } else {
      throw new Error('Failed to add');
    }
  } catch (e) {
    if (toast) toast.error('Failed to add item');
  } finally {
    adding.value = false;
  }
};

/**
 * Delete an item by ID.
 */
const deleteItem = async (id) => {
  if (confirm('Are you sure you want to delete this item?')) {
    try {
      const res = await fetch(api.url(`/items/${id}/`), { method: 'DELETE' });
      if (res.ok) {
        await fetchItems();
        if (toast) toast.success('Item deleted');
      } else {
        throw new Error('Delete failed');
      }
    } catch (e) {
      if (toast) toast.error('Failed to delete item');
    }
  }
};

// Fetch initial data on mount
onMounted(fetchItems);
</script>

<style scoped>
/* 
  Vue Transition Styles 
  Standard platform practice for smooth list interactions.
*/
.list-enter-active,
.list-leave-active {
  transition: all 0.4s ease;
}
.list-enter-from {
  opacity: 0;
  transform: translateX(-30px);
}
.list-leave-to {
  opacity: 0;
  transform: translateX(30px);
}
</style>
