<template>
  <!-- 
    A full-page extension component. 
    Styled to match the core platform aesthetic with consistent spacing and card patterns.
  -->
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <h1 class="text-2xl font-bold text-gray-900 mb-2">Example Extension</h1>
      <p class="text-sm text-gray-600">
        This extension demonstrates CRUD operations for both custom items and geostore features.
      </p>
    </div>
    
    <!-- Example Items CRUD Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <h2 class="text-xl font-semibold text-gray-900 mb-4">Example Items</h2>
    
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
        <div v-for="item in items" :key="item.id" class="flex justify-between items-center p-4 sm:p-5 border border-gray-200 rounded-lg hover:bg-gray-50 transition-all bg-white">
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
    <div v-else class="text-center py-12">
      <div class="mx-auto w-12 h-12 text-gray-400 mb-4">
        <svg class="w-12 h-12 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4"></path>
        </svg>
      </div>
      <h3 class="text-sm font-medium text-gray-900">No items found</h3>
      <p class="mt-1 text-sm text-gray-500">Add your first item using the form above.</p>
    </div>
    </div>
    
    <!-- Geostore Feature CRUD Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <h2 class="text-xl font-semibold text-gray-900 mb-6">Geostore Feature Operations</h2>
      
      <!-- Create Feature Section -->
      <div class="mb-6 p-4 sm:p-6 bg-gray-50 rounded-lg">
        <h3 class="text-lg font-semibold text-gray-900 mb-4">Create New Feature</h3>
        <div class="space-y-4">
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">Latitude</label>
              <input 
                v-model.number="newFeature.latitude" 
                type="number" 
                step="any"
                placeholder="e.g., 37.7749" 
                class="w-full border border-gray-300 px-4 py-2 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all shadow-sm" 
              />
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">Longitude</label>
              <input 
                v-model.number="newFeature.longitude" 
                type="number" 
                step="any"
                placeholder="e.g., -122.4194" 
                class="w-full border border-gray-300 px-4 py-2 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all shadow-sm" 
              />
            </div>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Name *</label>
            <input 
              v-model="newFeature.name" 
              placeholder="Feature name..." 
              class="w-full border border-gray-300 px-4 py-2 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all shadow-sm" 
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Description</label>
            <textarea 
              v-model="newFeature.description" 
              placeholder="Optional description..." 
              rows="2"
              class="w-full border border-gray-300 px-4 py-2 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all shadow-sm" 
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Tags (comma-separated)</label>
            <input 
              v-model="newFeature.tagsInput" 
              placeholder="tag1, tag2, tag3" 
              class="w-full border border-gray-300 px-4 py-2 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all shadow-sm" 
            />
          </div>
          <BaseButton 
            @click="createFeature" 
            :disabled="!canCreateFeature || creatingFeature"
            class="w-full"
          >
            <Loader v-if="creatingFeature" size="sm" layout="inline" :showMessage="false" color="white" class="mr-2" />
            Create Feature
          </BaseButton>
        </div>
      </div>
      
      <!-- Feature List Section -->
      <div class="mt-6">
        <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 mb-4">
          <h3 class="text-lg font-semibold text-gray-900">Your Features</h3>
          <BaseButton variant="primary" size="sm" @click="fetchFeatures" :disabled="loadingFeatures">
            <Loader v-if="loadingFeatures" size="sm" layout="inline" :showMessage="false" class="mr-2" />
            Refresh
          </BaseButton>
        </div>
        
        <!-- Loading State -->
        <div v-if="loadingFeatures" class="py-12 flex justify-center">
          <Loader size="md" message="Loading features..." />
        </div>
        
        <!-- Feature List -->
        <div v-else-if="features.length > 0" class="space-y-4">
          <TransitionGroup name="list">
            <div 
              v-for="feature in features" 
              :key="feature.properties.database_id" 
              class="p-4 sm:p-5 border border-gray-200 rounded-lg hover:bg-gray-50 transition-all bg-white"
            >
              <div class="flex justify-between items-start">
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-2 mb-2">
                    <span class="font-bold text-gray-900 text-lg">{{ feature.properties.name || 'Unnamed Feature' }}</span>
                    <span 
                      v-if="hasSpecialTag(feature)" 
                      class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800"
                    >
                      Special Tag
                    </span>
                  </div>
                  <p class="text-sm text-gray-500 mb-2 line-clamp-2">
                    {{ feature.properties.description || 'No description' }}
                  </p>
                  <div class="flex flex-wrap gap-2 mb-2">
                    <span 
                      v-for="tag in (feature.properties.tags || [])" 
                      :key="tag"
                      class="inline-flex items-center px-2.5 py-0.5 rounded-md text-xs font-medium bg-gray-100 text-gray-800"
                    >
                      {{ tag }}
                    </span>
                    <span v-if="!feature.properties.tags || feature.properties.tags.length === 0" class="text-xs text-gray-400 italic">
                      No tags
                    </span>
                  </div>
                  <div class="text-xs text-gray-400">
                    ID: {{ feature.properties.database_id }} | 
                    Type: {{ feature.geometry?.type || 'Unknown' }}
                  </div>
                </div>
                <div class="ml-4 flex-shrink-0 flex gap-2">
                  <BaseButton 
                    v-if="feature.properties.database_id"
                    tag="router-link"
                    :to="{ path: '/map', query: { featureId: feature.properties.database_id } }"
                    variant="primary"
                    color="blue"
                    size="sm"
                    title="View on Map"
                    @click.stop
                  >
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
                    </svg>
                  </BaseButton>
                  <BaseButton 
                    v-if="!hasSpecialTag(feature)"
                    variant="white" 
                    size="sm" 
                    @click="modifyFeature(feature.properties.database_id)"
                    :disabled="modifyingFeatureId === feature.properties.database_id"
                    title="Add special tag"
                  >
                    <Loader 
                      v-if="modifyingFeatureId === feature.properties.database_id" 
                      size="sm" 
                      layout="inline" 
                      :showMessage="false" 
                      class="mr-1" 
                    />
                    Add Special Tag
                  </BaseButton>
                  <BaseButton 
                    variant="secondary" 
                    color="red" 
                    size="sm" 
                    @click="deleteFeature(feature.properties.database_id)"
                    :disabled="deletingFeatureId === feature.properties.database_id"
                    title="Delete feature"
                  >
                    <Loader 
                      v-if="deletingFeatureId === feature.properties.database_id" 
                      size="sm" 
                      layout="inline" 
                      :showMessage="false" 
                      class="mr-1" 
                    />
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
                    </svg>
                  </BaseButton>
                </div>
              </div>
            </div>
          </TransitionGroup>
        </div>
        
        <!-- Empty State -->
        <div v-else class="text-center py-12">
          <div class="mx-auto w-12 h-12 text-gray-400 mb-4">
            <svg class="w-12 h-12 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"></path>
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 11a3 3 0 11-6 0 3 3 0 016 0z"></path>
            </svg>
          </div>
          <h3 class="text-sm font-medium text-gray-900">No features found</h3>
          <p class="mt-1 text-sm text-gray-500">Create your first feature using the form above.</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, inject, computed } from 'vue';

/*
  API INJECTION
  The 'api' object is an ExtensionApi instance injected from main.js.
  It provides convenience methods (get, post, put, delete) with automatic:
  - CSRF token handling
  - URL scoping (/api/extensions/<kebab-name>/)
  - Error handling and toast notifications
*/
const api = inject('extensionApi');

/*
  TOAST UTILITY
  Access the platform's native notification system.
  Use toast for success messages, error messages, and custom notifications.
  ExtensionApi does NOT automatically show toasts - you handle errors explicitly.
*/
const toast = window.gv_core.GeoVault.toast;

// Local component state for items
const items = ref([]);
const newItem = ref({ name: '', description: '' });
const loading = ref(true);
const adding = ref(false);

// Local component state for features
const features = ref([]);
const newFeature = ref({
  latitude: null,
  longitude: null,
  name: '',
  description: '',
  tagsInput: ''
});
const loadingFeatures = ref(false);
const creatingFeature = ref(false);
const modifyingFeatureId = ref(null);
const deletingFeatureId = ref(null);

/**
 * Fetch items from the extension's backend.
 * Uses ExtensionApi.get() which handles CSRF tokens automatically.
 */
const fetchItems = async () => {
  loading.value = true;
  try {
    // api.get() automatically handles CSRF token
    const response = await api.get('/items/');
    items.value = response.data;
  } catch (e) {
    // Handle errors explicitly
    const errorInfo = api.handleError(e);
    if (toast) toast.error(errorInfo.message);
    console.error('Failed to fetch items:', e);
  } finally {
    loading.value = false;
  }
};

/**
 * Create a new item.
 * Uses ExtensionApi.post() which handles CSRF tokens automatically.
 */
const addItem = async () => {
  const name = newItem.value.name.trim();
  if (!name || adding.value) return;
  
  adding.value = true;
  try {
    // api.post() automatically handles CSRF token and JSON serialization
    await api.post('/items/', { name, description: '' });
    newItem.value.name = ''; // Clear input
    await fetchItems();      // Refresh list
    if (toast) toast.success('Item added successfully');
  } catch (e) {
    // Handle errors explicitly
    const errorInfo = api.handleError(e);
    if (toast) toast.error(errorInfo.message || 'Failed to add item');
    console.error('Failed to add item:', e);
  } finally {
    adding.value = false;
  }
};

/**
 * Delete an item by ID.
 * Uses ExtensionApi.delete() which handles CSRF tokens automatically.
 */
const deleteItem = async (id) => {
  if (confirm('Are you sure you want to delete this item?')) {
    try {
      // api.delete() automatically handles CSRF token
      await api.delete(`/items/${id}/`);
      await fetchItems(); // Refresh list
      if (toast) toast.success('Item deleted');
    } catch (e) {
      // Handle errors explicitly
      const errorInfo = api.handleError(e);
      if (toast) toast.error(errorInfo.message || 'Failed to delete item');
      console.error('Failed to delete item:', e);
    }
  }
};

/**
 * Check if a feature has the special tag
 */
const hasSpecialTag = (feature) => {
  const tags = feature.properties?.tags || [];
  return tags.includes('example-extension:special');
};

/**
 * Computed property to check if we can create a feature
 */
const canCreateFeature = computed(() => {
  return newFeature.value.latitude !== null &&
         newFeature.value.longitude !== null &&
         newFeature.value.name.trim() !== '' &&
         !creatingFeature.value;
});

/**
 * Fetch all features from the main platform API
 */
const fetchFeatures = async () => {
  loadingFeatures.value = true;
  try {
    // Use fetch directly for main platform API (not extension API)
    const response = await fetch('/api/features/all/', {
      credentials: 'include',
      headers: {
        'X-CSRFToken': getCookie('csrftoken') || ''
      }
    });
    
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }
    
    const data = await response.json();
    if (data.data && data.data.features) {
      features.value = data.data.features;
    } else {
      features.value = [];
    }
  } catch (e) {
    console.error('Failed to fetch features:', e);
    api.toastError(e, 'Failed to load features');
    features.value = [];
  } finally {
    loadingFeatures.value = false;
  }
};

/**
 * Create a new feature
 */
const createFeature = async () => {
  if (!canCreateFeature.value || creatingFeature.value) return;
  
  creatingFeature.value = true;
  try {
    // Parse tags from comma-separated string
    const tags = newFeature.value.tagsInput
      .split(',')
      .map(t => t.trim())
      .filter(t => t.length > 0);
    
    const payload = {
      latitude: parseFloat(newFeature.value.latitude),
      longitude: parseFloat(newFeature.value.longitude),
      name: newFeature.value.name.trim(),
      description: newFeature.value.description.trim(),
      tags: tags
    };
    
    // Use extension API to create feature
    const response = await api.post('/features/create/', payload);
    
    // Clear form
    newFeature.value = {
      latitude: null,
      longitude: null,
      name: '',
      description: '',
      tagsInput: ''
    };
    
    // Refresh feature list
    await fetchFeatures();
    
    if (toast) toast.success('Feature created successfully');
  } catch (e) {
    const errorInfo = api.handleError(e);
    if (toast) toast.error(errorInfo.message || 'Failed to create feature');
    console.error('Failed to create feature:', e);
  } finally {
    creatingFeature.value = false;
  }
};

/**
 * Modify a feature by adding the special tag
 */
const modifyFeature = async (featureId) => {
  if (modifyingFeatureId.value === featureId) return;
  
  modifyingFeatureId.value = featureId;
  try {
    // Use extension API to modify feature
    const response = await api.post(`/features/${featureId}/modify/`);
    
    // Refresh feature list
    await fetchFeatures();
    
    if (toast) toast.success('Feature modified: special tag added');
  } catch (e) {
    const errorInfo = api.handleError(e);
    if (toast) toast.error(errorInfo.message || 'Failed to modify feature');
    console.error('Failed to modify feature:', e);
  } finally {
    modifyingFeatureId.value = null;
  }
};

/**
 * Delete a feature
 */
const deleteFeature = async (featureId) => {
  if (!confirm('Are you sure you want to delete this feature? This action cannot be undone.')) {
    return;
  }
  
  deletingFeatureId.value = featureId;
  try {
    // Use extension API to delete feature
    await api.delete(`/features/${featureId}/delete/`);
    
    // Refresh feature list
    await fetchFeatures();
    
    if (toast) toast.success('Feature deleted successfully');
  } catch (e) {
    const errorInfo = api.handleError(e);
    if (toast) toast.error(errorInfo.message || 'Failed to delete feature');
    console.error('Failed to delete feature:', e);
  } finally {
    deletingFeatureId.value = null;
  }
};

/**
 * Helper function to get CSRF cookie
 */
const getCookie = (name) => {
  const value = `; ${document.cookie}`;
  const parts = value.split(`; ${name}=`);
  if (parts.length === 2) return parts.pop().split(';').shift();
  return null;
};

// Fetch initial data on mount
onMounted(() => {
  fetchItems();
  fetchFeatures();
});
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
