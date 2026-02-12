<template>
  <div class="h-full flex flex-col bg-gray-50">
    <!-- Header -->
    <header class="bg-white shadow z-10">
      <div class="max-w-7xl mx-auto py-4 px-4 sm:px-6 lg:px-8 flex justify-between items-center">
        <h1 class="text-2xl font-bold text-gray-900 flex items-center">
          <MapPinIcon class="h-8 w-8 text-blue-600 mr-2" />
          Places
        </h1>
        <button
          @click="showCreateModal = true"
          class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md shadow-sm text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
        >
          <PlusIcon class="h-5 w-5 mr-2" />
          Add Place
        </button>
      </div>
    </header>

    <!-- Content -->
    <main class="flex-1 overflow-hidden flex">
      <!-- List Sidebar -->
      <div class="w-96 flex flex-col border-r border-gray-200 bg-white overflow-hidden">
        <!-- Search -->
        <div class="p-4 border-b border-gray-200">
          <div class="relative rounded-md shadow-sm">
            <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
              <MagnifyingGlassIcon class="h-5 w-5 text-gray-400" aria-hidden="true" />
            </div>
            <input
              type="text"
              v-model="searchQuery"
              class="focus:ring-blue-500 focus:border-blue-500 block w-full pl-10 sm:text-sm border-gray-300 rounded-md"
              placeholder="Search places..."
            />
          </div>
        </div>

        <!-- List -->
        <div class="flex-1 overflow-y-auto">
          <div v-if="loading" class="p-4 text-center text-gray-500">
            <Loader class="mx-auto h-8 w-8 text-blue-500" />
            <p class="mt-2 text-sm">Loading...</p>
          </div>
          <div v-else-if="filteredPlaces.length === 0" class="p-8 text-center text-gray-500">
            <MapPinIcon class="mx-auto h-12 w-12 text-gray-300" />
            <p class="mt-2 text-lg font-medium">No places found</p>
            <p class="text-sm">Get started by creating a new place.</p>
          </div>
          <ul v-else class="divide-y divide-gray-200">
            <li
              v-for="place in filteredPlaces"
              :key="place.properties.database_id"
              @click="selectPlace(place)"
              :class="[
                'cursor-pointer hover:bg-gray-50 transition-colors duration-150',
                selectedPlace?.properties?.database_id === place.properties.database_id ? 'bg-blue-50 border-l-4 border-blue-500' : 'pl-4'
              ]"
            >
              <div class="px-4 py-4 sm:px-6">
                <div class="flex items-center justify-between">
                  <p class="text-sm font-medium text-blue-600 truncate">
                    {{ place.properties.name || 'Unnamed Place' }}
                  </p>
                  <div class="ml-2 flex-shrink-0 flex">
                    <span
                      class="px-2 inline-flex text-xs leading-5 font-semibold rounded-full bg-green-100 text-green-800"
                    >
                      {{ formatCoords(place.geometry.coordinates) }}
                    </span>
                  </div>
                </div>
                <div class="mt-2 sm:flex sm:justify-between">
                  <div class="sm:flex">
                    <p class="flex items-center text-sm text-gray-500">
                      {{ truncate(place.properties.description, 50) }}
                    </p>
                  </div>
                </div>
              </div>
            </li>
          </ul>
        </div>
      </div>

      <!-- Map Area -->
      <div class="flex-1 relative bg-gray-100">
        <div ref="mapContainer" class="absolute inset-0"></div>
        
        <!-- Place Detail Overlay -->
        <div 
            v-if="selectedPlace"
            class="absolute top-4 right-4 w-80 bg-white rounded-lg shadow-lg p-4 z-10 transition-all transform duration-300"
        >
             <button @click="selectedPlace = null" class="absolute top-2 right-2 text-gray-400 hover:text-gray-600">
                <XMarkIcon class="h-5 w-5" />
             </button>
             <h3 class="text-lg font-bold text-gray-900 mb-2">{{ selectedPlace.properties.name }}</h3>
             <p class="text-sm text-gray-600 mb-4">{{ selectedPlace.properties.description || 'No description' }}</p>
             
             <div class="flex justify-between mt-4">
                 <button 
                    @click="editPlace(selectedPlace)" 
                    class="text-sm text-blue-600 hover:text-blue-800 font-medium"
                 >
                     Edit
                 </button>
                 <button 
                    @click="deletePlace(selectedPlace)" 
                    class="text-sm text-red-600 hover:text-red-800 font-medium"
                 >
                     Delete
                 </button>
                 <a 
                    :href="googleMapsUrl(selectedPlace)" 
                    target="_blank" 
                    class="text-sm text-gray-600 hover:text-gray-900 flex items-center"
                 >
                     Open in Maps <ArrowTopRightOnSquareIcon class="h-3 w-3 ml-1"/>
                 </a>
             </div>
        </div>
      </div>
    </main>

    <!-- Create/Edit Modal -->
    <div v-if="showCreateModal" class="fixed z-50 inset-0 overflow-y-auto" aria-labelledby="modal-title" role="dialog" aria-modal="true">
      <div class="flex items-end justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
        <div class="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" aria-hidden="true" @click="showCreateModal = false"></div>

        <span class="hidden sm:inline-block sm:align-middle sm:h-screen" aria-hidden="true">&#8203;</span>

        <div class="inline-block align-bottom bg-white rounded-lg text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-lg sm:w-full">
          <div class="bg-white px-4 pt-5 pb-4 sm:p-6 sm:pb-4">
            <h3 class="text-lg leading-6 font-medium text-gray-900" id="modal-title">
              {{ isEditing ? 'Edit Place' : 'Create New Place' }}
            </h3>
            <div class="mt-4 space-y-4">
              <div>
                <label class="block text-sm font-medium text-gray-700">Name</label>
                <input type="text" v-model="form.name" class="mt-1 focus:ring-blue-500 focus:border-blue-500 block w-full shadow-sm sm:text-sm border-gray-300 rounded-md" />
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700">Description</label>
                <textarea v-model="form.description" rows="3" class="mt-1 focus:ring-blue-500 focus:border-blue-500 block w-full shadow-sm sm:text-sm border-gray-300 rounded-md"></textarea>
              </div>
              <div class="grid grid-cols-2 gap-4">
                  <div>
                    <label class="block text-sm font-medium text-gray-700">Latitude</label>
                    <input type="number" step="any" v-model.number="form.latitude" class="mt-1 focus:ring-blue-500 focus:border-blue-500 block w-full shadow-sm sm:text-sm border-gray-300 rounded-md" />
                  </div>
                  <div>
                    <label class="block text-sm font-medium text-gray-700">Longitude</label>
                    <input type="number" step="any" v-model.number="form.longitude" class="mt-1 focus:ring-blue-500 focus:border-blue-500 block w-full shadow-sm sm:text-sm border-gray-300 rounded-md" />
                  </div>
              </div>
            </div>
          </div>
          <div class="bg-gray-50 px-4 py-3 sm:px-6 sm:flex sm:flex-row-reverse">
            <button
              type="button"
              @click="savePlace"
              class="w-full inline-flex justify-center rounded-md border border-transparent shadow-sm px-4 py-2 bg-blue-600 text-base font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 sm:ml-3 sm:w-auto sm:text-sm"
            >
              Save
            </button>
            <button
              type="button"
              @click="showCreateModal = false"
              class="mt-3 w-full inline-flex justify-center rounded-md border border-gray-300 shadow-sm px-4 py-2 bg-white text-base font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 sm:mt-0 sm:ml-3 sm:w-auto sm:text-sm"
            >
              Cancel
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, onMounted, inject, computed, watch, shallowRef } from 'vue';
import { MapPinIcon, PlusIcon, MagnifyingGlassIcon, XMarkIcon, ArrowTopRightOnSquareIcon } from '@heroicons/vue/24/outline';

export default {
  components: {
    MapPinIcon,
    PlusIcon,
    MagnifyingGlassIcon,
    XMarkIcon,
    ArrowTopRightOnSquareIcon
  },
  setup() {
    const api = inject('placesExtensionApi');
    const toast = inject('toast'); // Assuming global toast injection or verify availability

    const places = ref([]);
    const loading = ref(true);
    const searchQuery = ref('');
    const selectedPlace = ref(null);
    const mapContainer = ref(null);
    const map = shallowRef(null);
    const vectorSource = shallowRef(null);

    const showCreateModal = ref(false);
    const isEditing = ref(false);
    
    const form = ref({
        id: null,
        name: '',
        description: '',
        latitude: 0,
        longitude: 0
    });

    const filteredPlaces = computed(() => {
      if (!searchQuery.value) return places.value;
      const lower = searchQuery.value.toLowerCase();
      return places.value.filter(p => 
        (p.properties.name && p.properties.name.toLowerCase().includes(lower)) ||
        (p.properties.description && p.properties.description.toLowerCase().includes(lower))
      );
    });

    const fetchPlaces = async () => {
      loading.value = true;
      try {
        const res = await api.get('/features/');
        places.value = res.data.features || [];
        updateMapFeatures();
      } catch (err) {
        console.error("Failed to load places", err);
        // Error handling if toast is available
      } finally {
        loading.value = false;
      }
    };

    const initMap = () => {
        if (!window.ol) return;
        
        vectorSource.value = new window.ol.source.Vector();
        
        const vectorLayer = new window.ol.layer.Vector({
            source: vectorSource.value,
            style: new window.ol.style.Style({
                image: new window.ol.style.Circle({
                    radius: 7,
                    fill: new window.ol.style.Fill({color: '#2563EB'}),
                    stroke: new window.ol.style.Stroke({color: 'white', width: 2})
                })
            })
        });

        map.value = new window.ol.Map({
            target: mapContainer.value,
            layers: [
                new window.ol.layer.Tile({
                    source: new window.ol.source.OSM()
                }),
                vectorLayer
            ],
            view: new window.ol.View({
                center: window.ol.proj.fromLonLat([0, 0]),
                zoom: 2
            })
        });

        // Click handler
        map.value.on('click', (e) => {
            const feature = map.value.forEachFeatureAtPixel(e.pixel, feature => feature);
            if (feature) {
                const placeId = feature.get('database_id');
                const place = places.value.find(p => p.properties.database_id === placeId);
                if (place) {
                    selectPlace(place);
                }
            } else {
                // Determine coords for new place?
                // For now, maybe just deselect
                selectedPlace.value = null;
            }
        });
    };

    const updateMapFeatures = () => {
        if (!vectorSource.value || !window.ol) return;
        
        vectorSource.value.clear();
        
        if (places.value.length === 0) return;

        const features = places.value.map(place => {
            const coords = place.geometry.coordinates; // [lon, lat, elev]
            const feature = new window.ol.Feature({
                geometry: new window.ol.geom.Point(window.ol.proj.fromLonLat([coords[0], coords[1]])),
                database_id: place.properties.database_id
            });
            return feature;
        });
        
        vectorSource.value.addFeatures(features);
        
        // Fit view to extent
        if (features.length > 0) {
           const extent = vectorSource.value.getExtent();
           map.value.getView().fit(extent, { padding: [50, 50, 50, 50], maxZoom: 15 });
        }
    };

    const selectPlace = (place) => {
        selectedPlace.value = place;
        if (map.value && place.geometry.coordinates) {
             const coords = window.ol.proj.fromLonLat([place.geometry.coordinates[0], place.geometry.coordinates[1]]);
             map.value.getView().animate({
                 center: coords,
                 zoom: 12,
                 duration: 1000
             });
        }
    };

    const savePlace = async () => {
        const payload = {
            type: 'Feature',
            geometry: {
                type: 'Point',
                coordinates: [form.value.longitude, form.value.latitude]
            },
            properties: {
                name: form.value.name,
                description: form.value.description
            }
        };

        try {
            if (isEditing.value && form.value.id) {
                await api.put(`/features/${form.value.id}/`, payload);
            } else {
                await api.post('/features/', payload);
            }
            
            showCreateModal.value = false;
            fetchPlaces();
            resetForm();
        } catch (err) {
            console.error("Failed to save place", err);
            // toast error
        }
    };

    const editPlace = (place) => {
        isEditing.value = true;
        form.value = {
            id: place.properties.database_id,
            name: place.properties.name,
            description: place.properties.description,
            latitude: place.geometry.coordinates[1],
            longitude: place.geometry.coordinates[0]
        };
        showCreateModal.value = true;
    };
    
    const deletePlace = async (place) => {
        if (!confirm(`Are you sure you want to delete "${place.properties.name}"?`)) return;
        try {
             await api.delete(`/features/${place.properties.database_id}/`);
             selectedPlace.value = null;
             fetchPlaces();
        } catch (err) {
            console.error(err);
        }
    };

    const resetForm = () => {
        isEditing.value = false;
        form.value = { id: null, name: '', description: '', latitude: 0, longitude: 0 };
    };
    
    const formatCoords = (coords) => {
        if (!coords || coords.length < 2) return '';
        return `${coords[1].toFixed(4)}, ${coords[0].toFixed(4)}`;
    };

    const truncate = (str, n) => {
        return (str && str.length > n) ? str.substr(0, n-1) + '...' : str;
    };
    
    const googleMapsUrl = (place) => {
        const lat = place.geometry.coordinates[1];
        const lon = place.geometry.coordinates[0];
        return `https://www.google.com/maps/search/?api=1&query=${lat},${lon}`;
    };

    watch(showCreateModal, (val) => {
        if (!val) resetForm();
    });

    onMounted(async () => {
        initMap();
        await fetchPlaces();
    });

    return {
      places,
      loading,
      searchQuery,
      filteredPlaces,
      selectedPlace,
      mapContainer,
      showCreateModal,
      form,
      isEditing,
      
      selectPlace,
      savePlace,
      editPlace,
      deletePlace,
      formatCoords,
      truncate,
      googleMapsUrl
    };
  }
}
</script>
