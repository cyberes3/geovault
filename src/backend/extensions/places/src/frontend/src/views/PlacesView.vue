<template>
  <div class="space-y-6 px-4 sm:px-6 lg:px-8 pt-6">
    <!-- Page Header (matches Collections page) -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 mb-4">
        <div>
          <h1 class="text-xl sm:text-2xl font-bold text-gray-900 mb-1 sm:mb-2">Places</h1>
        </div>
        <BaseButton
          @click="goToNewPlace"
          class="w-full sm:w-auto"
          variant="primary"
          color="blue"
          size="md"
          title="Add a new place"
        >
          <PlusIcon class="h-5 w-5 mr-2" />
          Add Place
        </BaseButton>
      </div>

      <!-- Explanatory Text -->
      <div class="mt-2 sm:mt-4 p-4 bg-blue-50 border border-blue-200 rounded-lg">
        <p class="text-sm text-gray-700">
          Places are saved point locations you can name, describe, and view on the map. Create places to bookmark locations, plan trips, or keep a list of spots you want to remember. You can edit or delete places anytime and open any place in Google Maps.
        </p>
      </div>
    </div>

    <!-- List + Map row -->
    <div class="flex gap-3 flex-1 min-h-0" style="min-height: 480px;">
      <!-- List panel (50% width, card style) -->
      <div class="w-1/2 min-w-0 flex flex-col bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden relative">
        <!-- Loading overlay: grey out and disable list while refreshing -->
        <div
          v-if="loading"
          class="absolute inset-0 z-10 flex flex-col items-center justify-center bg-white/50 pointer-events-auto cursor-wait rounded-lg"
          aria-busy="true"
          aria-live="polite"
        >
          <div class="inline-flex bg-white rounded-lg shadow-lg border border-gray-200 px-4 py-3">
            <Loader size="sm" layout="inline" :show-message="true" message="Loading places..." />
          </div>
        </div>
        <!-- Search -->
        <div class="p-4 border-b border-gray-200">
          <div class="relative">
            <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
              <MagnifyingGlassIcon class="h-5 w-5 text-gray-400" aria-hidden="true" />
            </div>
            <input
              type="text"
              v-model="searchQuery"
              class="block w-full pl-10 pr-3 py-2 border border-gray-300 rounded-lg shadow-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all sm:text-sm"
              placeholder="Search places..."
            />
          </div>
        </div>

        <!-- List -->
        <div class="flex-1 overflow-y-auto p-4">
          <div v-if="filteredPlaces.length === 0 && !loading" class="text-center py-12">
            <div class="mx-auto w-12 h-12 text-gray-400 mb-4">
              <MapPinIcon class="w-12 h-12 mx-auto" />
            </div>
            <h3 class="text-sm font-medium text-gray-900">No places found</h3>
            <p class="mt-1 text-sm text-gray-500">Get started by creating a new place.</p>
          </div>
          <div v-else class="space-y-4">
            <div
              v-for="place in filteredPlaces"
              :key="place.properties.database_id"
              @click="selectPlace(place)"
              :class="[
                'group cursor-pointer p-4 sm:p-5 border rounded-lg transition-all',
                selectedPlace?.properties?.database_id === place.properties.database_id
                  ? 'border-blue-500 bg-blue-50'
                  : 'border-gray-200 bg-white hover:bg-gray-50'
              ]"
            >
              <div class="flex items-center justify-between gap-2">
                <span class="font-bold text-gray-900 text-lg block truncate min-w-0">
                  {{ place.properties.name || 'Unnamed Place' }}
                </span>
                <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-800 flex-shrink-0">
                  {{ formatCoords(place.geometry.coordinates) }}
                </span>
              </div>
              <p class="text-sm text-gray-500 mt-1 line-clamp-1">
                {{ truncate(place.properties.description, 50) || 'No description' }}
              </p>
              <div
                :class="[
                  'flex flex-wrap items-center gap-2 mt-3 transition-opacity',
                  selectedPlace?.properties?.database_id === place.properties.database_id ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
                ]"
                @click.stop
              >
                <BaseButton variant="primary" color="blue" size="sm" @click.stop="editPlace(place)">
                  Edit
                </BaseButton>
                <BaseButton variant="secondary" color="red" size="sm" @click.stop="deletePlace(place)">
                  Delete
                </BaseButton>
                <a
                  :href="googleMapsUrl(place)"
                  target="_blank"
                  rel="noopener noreferrer"
                  title="Open in Google Maps"
                  class="group/maps inline-flex items-center relative"
                >
                  <span class="relative inline-block w-5 h-5">
                    <img :src="googleMapsIconUrl" alt="" class="absolute inset-0 w-5 h-5 opacity-0 group-hover/maps:opacity-100 transition-opacity" aria-hidden="true" />
                    <img :src="googleMapsIconBwUrl" alt="Open in Google Maps" class="w-5 h-5 opacity-100 group-hover/maps:opacity-0 transition-opacity" />
                  </span>
                </a>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Map (50% width, card style) -->
      <div class="w-1/2 min-w-0 relative bg-gray-100 rounded-lg border border-gray-200 overflow-hidden">
        <div ref="mapContainer" class="absolute inset-0"></div>
      </div>
    </div>

  </div>
</template>

<script>
import { ref, onMounted, onActivated, inject, computed, shallowRef } from 'vue';
import { MapPinIcon, PlusIcon, MagnifyingGlassIcon } from '@heroicons/vue/24/outline';
import googleMapsIconUrl from '@/assets/google-maps-icon.svg';
import googleMapsIconBwUrl from '@/assets/google-maps-icon-bw.svg';

export default {
  components: {
    MapPinIcon,
    PlusIcon,
    MagnifyingGlassIcon
  },
  setup() {
    const api = inject('placesExtensionApi');
    const placesRouter = inject('placesExtensionRouter');
    const toast = inject('toast');

    const places = ref([]);
    const loading = ref(true);
    const searchQuery = ref('');
    const selectedPlace = ref(null);
    const mapContainer = ref(null);
    const map = shallowRef(null);
    const vectorSource = shallowRef(null);

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
      // Keep existing list visible under overlay; only replace when new data arrives
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
            controls: [],
            layers: [
                new window.ol.layer.Tile({
                    source: new window.ol.source.OSM({ attributions: [] })
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

    const editPlace = (place) => {
        if (placesRouter) placesRouter.navigate('/new?edit=' + place.properties.database_id);
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

    const goToNewPlace = () => {
        if (placesRouter) placesRouter.navigate('/new');
    };

    onMounted(() => {
        initMap();
    });

    onActivated(() => {
        fetchPlaces();
    });

    return {
      places,
      loading,
      searchQuery,
      filteredPlaces,
      selectedPlace,
      mapContainer,

      goToNewPlace,
      selectPlace,
      editPlace,
      deletePlace,
      formatCoords,
      truncate,
      googleMapsUrl,
      googleMapsIconUrl,
      googleMapsIconBwUrl
    };
  }
}
</script>
