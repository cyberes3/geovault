<template>
  <div class="flex flex-col flex-1 min-h-0 h-full w-full overflow-y-auto sm:overflow-hidden bg-gray-50">
    <!-- Single-page scroll layout: map has fixed viewport height and form flows below it. -->
    <div
        class="h-[42vh] min-h-[240px] max-h-[440px] sm:h-auto sm:max-h-none sm:min-h-[320px] sm:flex-1 sm:min-h-0 relative border-b border-gray-300"
    >
      <div ref="mapContainer" class="absolute inset-0 z-0 h-full w-full min-h-0 bg-gray-100 touch-pan-y"></div>

      <!-- Map loading overlay (edit place) -->
      <div
          v-if="loadingEdit"
          class="absolute inset-0 z-20 flex flex-col items-center justify-center bg-white/50 pointer-events-auto cursor-wait"
          aria-busy="true"
          aria-live="polite"
      >
        <div class="inline-flex bg-white rounded-lg shadow-lg border border-gray-200 px-4 py-3">
          <Loader size="sm" layout="inline" :show-message="true" message="Loading place..."/>
        </div>
      </div>

      <!-- Search bar (like Geotagger) -->
      <div class="absolute top-4 left-4 right-4 z-10 max-w-md">
        <div class="bg-white ring-1 ring-black/5 flex items-center p-1"
             :class="(showResults && (searchResults.length > 0 || (searchQuery && !isSearching && searchTimeout === null))) ? 'rounded-t-lg' : 'rounded-lg'">
          <input
              v-model="searchQuery"
              @input="handleSearchInput"
              @keyup.enter="performSearch"
              placeholder="Search locations..."
              class="flex-1 outline-none text-sm px-3 py-2 bg-transparent w-full"
          />
          <button type="button" @click="performSearch"
                  class="p-2 hover:bg-gray-100 rounded-md text-gray-500 transition-colors">
            <div class="w-5 h-5 flex items-center justify-center overflow-hidden">
              <Loader v-if="isSearching" size="sm" :show-message="false" class="!py-0 !mt-0"/>
              <MagnifyingGlassIcon v-else class="w-5 h-5"/>
            </div>
          </button>
        </div>

        <!-- Search results dropdown -->
        <div v-if="showResults && (searchResults.length > 0 || (searchQuery && !isSearching && searchTimeout === null))"
             class="bg-white ring-1 ring-black/5 max-h-60 overflow-y-auto w-full absolute top-full left-0 z-50 rounded-t-none rounded-b-lg">
          <div v-if="searchResults.length === 0" class="px-4 py-3 text-gray-500 text-sm italic">
            No results found
          </div>
          <div
              v-else
              v-for="result in searchResults"
              :key="result.id || result.text"
              @click="selectSearchResult(result)"
              class="px-4 py-2 hover:bg-gray-100 cursor-pointer border-b border-gray-100 last:border-0 transition-colors"
          >
            <p class="text-sm font-semibold text-gray-900 truncate">{{ result.text || result.place_name }}</p>
            <p v-if="result.place_name && result.place_name !== result.text"
               class="text-xs text-gray-500 truncate mt-0.5">{{ result.place_name }}</p>
          </div>
        </div>
      </div>

      <div
          class="absolute z-10 bottom-4 left-4 flex flex-col bg-white border border-gray-200 rounded shadow-md overflow-hidden">
        <button
            type="button"
            class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
            title="Choose Basemap"
            @click="openLayerPickerModal"
        >
          <Square3Stack3DIcon class="w-5 h-5"/>
        </button>
        <button
            type="button"
            class="p-2 border-t border-gray-200 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
            title="Go to Home Extent"
            @click="resetMapViewport"
        >
          <HomeIcon class="w-5 h-5"/>
        </button>
      </div>
    </div>

    <!-- Form: natural flow; page scrolls as one container -->
    <div
        class="flex flex-col bg-white border-t border-gray-300 shadow-[0_-2px_10px_rgba(0,0,0,0.05)] sm:flex-shrink-0 sm:z-20">
      <div>
        <div class="max-w-4xl mx-auto p-4 sm:p-6 space-y-4">
          <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">Name <span
                  class="text-red-500">*</span></label>
              <input
                  v-model="name"
                  type="text"
                  placeholder="Place name"
                  class="w-full border border-gray-300 px-4 py-2 rounded-lg shadow-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all sm:text-sm disabled:opacity-60 disabled:cursor-not-allowed"
                  :disabled="loadingEdit"
              />
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">Description</label>
              <textarea
                  v-model="description"
                  rows="4"
                  placeholder="Optional description"
                  class="w-full border border-gray-300 px-4 py-2 rounded-lg shadow-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all sm:text-sm resize-none disabled:opacity-60 disabled:cursor-not-allowed"
                  :disabled="loadingEdit"
              />
            </div>
          </div>
          <div class="space-y-1.5">
            <div class="flex items-center gap-2">
              <label class="text-xs font-semibold text-gray-500 uppercase tracking-wide">Coordinates or Address <span
                  class="text-red-500">*</span></label>
              <Loader v-if="isGeocoding" size="sm" :show-message="false" class="!py-0 !mt-0"/>
              <span v-if="coordinateError" class="text-xs text-red-600">{{ coordinateError }}</span>
            </div>
            <div class="flex flex-row flex-wrap gap-2 items-center">
              <div class="flex flex-1 min-w-[120px] items-center gap-2">
                <input
                    v-model="coordinatesInput"
                    type="text"
                    placeholder="37.7749, -122.4194"
                    class="flex-1 min-w-0 h-10 px-3 border border-gray-300 rounded-lg shadow-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 outline-none transition-all sm:text-sm disabled:opacity-60 disabled:cursor-not-allowed"
                    :disabled="loadingEdit"
                    @input="onCoordinatesInput"
                />
                <button
                    type="button"
                    class="h-10 w-10 flex-shrink-0 flex items-center justify-center rounded-lg text-gray-500 hover:bg-gray-100 hover:text-gray-700 transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                    :disabled="loadingEdit"
                    title="Parse Coordinates or Address"
                    @click="validateCoordinates"
                >
                  <ArrowPathIcon class="w-5 h-5"/>
                </button>
              </div>
              <BaseButton
                  type="button"
                  variant="white"
                  size="sm"
                  class="w-full sm:w-auto justify-center"
                  :disabled="isGettingLocation || loadingEdit"
                  title="Use Current Location"
                  @click="useCurrentLocation"
              >
                <Loader v-if="isGettingLocation" size="sm" layout="inline" :show-message="false"/>
                <MapPinIcon v-else class="w-5 h-5 text-gray-700"/>
                <span class="ml-1.5">Use my location</span>
              </BaseButton>
            </div>
          </div>
          <div class="flex flex-wrap gap-3 pt-2">
            <BaseButton
                type="button"
                variant="primary"
                color="blue"
                size="sm"
                :disabled="saving || loadingEdit || !name.trim() || !coordinatesInput.trim()"
                @click="savePlace"
            >
              <Loader v-if="saving" size="sm" layout="inline" :show-message="false" class="mr-2"/>
              {{ editId ? 'Update place' : 'Save place' }}
            </BaseButton>
            <BaseButton type="button" variant="white" size="sm" :disabled="loadingEdit" @click="goToList">
              Cancel
            </BaseButton>
          </div>
        </div>
      </div>
    </div>

    <BaseModal
        :is-open="showLayerPickerModal"
        title="Map Layer"
        max-width="md"
        fit-content-height
        :full-screen-mobile="false"
        @close="closeLayerPickerModal"
    >
      <div class="p-4 sm:p-6">
        <label for="place-edit-map-layer" class="block text-sm font-medium text-gray-700 mb-2">Basemap</label>
        <select
            id="place-edit-map-layer"
            v-model="selectedBaseSourceId"
            class="select-custom w-full px-3 py-2 text-sm border border-gray-300 rounded-lg shadow-sm focus:outline-none"
            :disabled="baseSourceOptions.length === 0"
            @change="applyBaseSourceSelection"
        >
          <option v-for="option in baseSourceOptions" :key="option.id" :value="option.id">
            {{ option.name }}
          </option>
        </select>
      </div>
      <template #footer>
        <BaseButton type="button" variant="white" @click="closeLayerPickerModal">
          Close
        </BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<script>
import {computed, inject, onBeforeUnmount, onDeactivated, onMounted, ref, watch} from 'vue';
import {onBeforeRouteLeave, useRoute} from 'vue-router';
import {ArrowPathIcon, HomeIcon, MagnifyingGlassIcon, MapPinIcon, Square3Stack3DIcon} from '@heroicons/vue/24/outline';
import {createPlacesMap} from '@/utils/placesMaplibre.js';
import {getDefaultMapSourceIdFromStore} from '@/utils/placesMapSettings.js';
const PLACE_EDIT_SOURCE_ID = 'gv_places_overlay_edit_source';
const PLACE_EDIT_LAYER_ID = 'gv_places_overlay_edit_layer';
const INITIAL_CENTER = [0, 0];
const INITIAL_ZOOM = 2;

export default {
  name: 'PlaceNewView',
  components: {
    Square3Stack3DIcon,
    ArrowPathIcon,
    HomeIcon,
    MagnifyingGlassIcon,
    MapPinIcon
  },
  setup() {
    const route = useRoute();
    const api = inject('extensionApi');
    const router = inject('extensionRouter');
    const utils = window.gv_core?.GeoVault?.utils ?? null;
    const toast = window.gv_core?.GeoVault?.toast ?? {
      success: () => {
      }, error: () => {
      }
    };

    const mapContainer = ref(null);
    const map = ref(null);
    const mapController = ref(null);
    const showLayerPickerModal = ref(false);
    const baseSourceOptions = ref([]);
    const selectedBaseSourceId = ref('osm');

    const editId = computed(() => {
      const q = route.query.edit;
      if (q == null || q === '') return null;
      const n = parseInt(String(q), 10);
      return isNaN(n) ? null : n;
    });

    const name = ref('');
    const description = ref('');
    const latitude = ref(null);
    const longitude = ref(null);
    const coordinatesInput = ref('');
    const coordinateError = ref('');
    const saving = ref(false);
    const loadingEdit = ref(false);
    const isGettingLocation = ref(false);

    const searchQuery = ref('');
    const searchResults = ref([]);
    const showResults = ref(false);
    const isSearching = ref(false);
    const searchTimeout = ref(null);
    const currentSearchQuery = ref('');

    const storedAddress = ref(null);
    const isGeocoding = ref(false);
    const addressSearchTimeout = ref(null);
    const coordinatesValidationTimeout = ref(null);
    const addressAbortController = ref(null);
    const lastAddressRequestId = ref(0);

    // Snapshot of name, description, lat, lon, address when form was loaded or reset (for dirty check)
    const initialFormSnapshot = ref(null);

    const isDirty = computed(() => {
      const s = initialFormSnapshot.value;
      if (s == null) return false;
      return name.value !== s.name ||
          description.value !== s.description ||
          latitude.value !== s.lat ||
          longitude.value !== s.lon ||
          (storedAddress.value || '') !== (s.address || '');
    });

    function setCoords(lat, lon, displayText = null) {
      latitude.value = lat == null ? null : parseFloat(Number(lat).toFixed(6));
      longitude.value = lon == null ? null : parseFloat(Number(lon).toFixed(6));
      if (displayText != null && displayText !== '') {
        coordinatesInput.value = displayText;
        storedAddress.value = displayText;
      } else {
        coordinatesInput.value = latitude.value != null && longitude.value != null
          ? `${latitude.value}, ${longitude.value}`
          : '';
        storedAddress.value = null;
      }
      coordinateError.value = '';
      updateMarkerFromCoords();
    }

    /** True if the string has a letter that is not a coordinate direction (N,S,E,W,D). Used to avoid geocoding inputs like "39.5 N, 104.8 W". */
    function hasAddressLikeLetters(str) {
      return /[a-zA-Z]/.test(str.replace(/[nsewd]/gi, ''));
    }

    function performAddressSearch(query) {
      if (addressAbortController.value) {
        addressAbortController.value.abort();
      }
      addressAbortController.value = new AbortController();
      const controller = addressAbortController.value;
      lastAddressRequestId.value += 1;
      const myId = lastAddressRequestId.value;
      isGeocoding.value = true;
      const url = `/api/geocoding/search/?q=${encodeURIComponent(query)}`;
      fetch(url, { credentials: 'include', signal: controller.signal })
        .then((response) => response.json().then((data) => ({ ok: response.ok, data })))
        .then(({ ok, data }) => {
          if (myId !== lastAddressRequestId.value) return;
          if (!ok) {
            coordinateError.value = (data && (data.message || data.error)) || 'Geocoding failed';
            return;
          }
          const list = data.data;
          if (list && list.length > 0 && list[0].coordinates && list[0].coordinates.length >= 2) {
            const [lon, lat] = list[0].coordinates;
            const placeName = list[0].place_name || query;
            setCoords(lat, lon, placeName);
            updateMarkerFromCoords(true);
            if (map.value) {
              map.value.easeTo({
                center: [lon, lat],
                duration: 300
              });
            }
          } else {
            coordinateError.value = 'Address not found';
          }
        })
        .catch((err) => {
          if (err.name === 'AbortError') return;
          if (myId !== lastAddressRequestId.value) return;
          coordinateError.value = err.message || 'Geocoding failed';
        })
        .finally(() => {
          if (myId === lastAddressRequestId.value) {
            isGeocoding.value = false;
          }
        });
    }

    function onCoordinatesInput() {
      const input = coordinatesInput.value.trim();
      if (!input) {
        coordinateError.value = '';
      }
    }

    // Unified rule (same as Android): try parse as coordinates; if fail, geocode only when
    // address-like (has letter not N/S/E/W/D); else if looks like coordinate attempt show error;
    // else clear with no error (e.g. "123 " while typing).
    function validateCoordinates() {
      coordinateError.value = '';
      latitude.value = null;
      longitude.value = null;
      storedAddress.value = null;
      const input = coordinatesInput.value.trim();
      if (!input) {
        return;
      }
      const parseCoordinates = window.gv_core?.GeoVault?.utils?.parseCoordinates;
      if (!parseCoordinates) return;
      const coordinates = parseCoordinates(input);
      if (coordinates) {
        latitude.value = coordinates.lat;
        longitude.value = coordinates.lng;
        coordinatesInput.value = `${latitude.value}, ${longitude.value}`;
        storedAddress.value = null;
        updateMarkerFromCoords(true);
        return;
      }
      if (hasAddressLikeLetters(input)) {
        if (addressAbortController.value) {
          addressAbortController.value.abort();
        }
        performAddressSearch(input);
        return;
      }
      const looksLikeCoordinates = window.gv_core?.GeoVault?.utils?.looksLikeCoordinates;
      if (looksLikeCoordinates && looksLikeCoordinates(input)) {
        coordinateError.value = 'Invalid coordinate format';
        updateMarkerFromCoords();
        return;
      }
      updateMarkerFromCoords();
    }

    function updateMarkerFromCoords(panMap = false) {
      if (!map.value || !mapController.value) return;
      const lat = latitude.value;
      const lon = longitude.value;
      const valid = lat != null && lon != null && isFinite(lat) && isFinite(lon);
      if (valid) {
        mapController.value.setPointFeatures([{
          type: 'Feature',
          geometry: {
            type: 'Point',
            coordinates: [lon, lat]
          },
          properties: {
            is_highlighted: 0
          }
        }]);
        if (panMap && map.value) {
          map.value.easeTo({
            center: [lon, lat],
            duration: 300
          });
        }
      } else {
        mapController.value.setPointFeatures([]);
      }
    }


    async function initMap() {
      if (mapController.value) {
        mapController.value.destroy();
        mapController.value = null;
      }
      if (!mapContainer.value) {
        return;
      }
      try {
        mapController.value = await createPlacesMap({
          container: mapContainer.value,
          mode: 'edit',
          sourceId: PLACE_EDIT_SOURCE_ID,
          layerId: PLACE_EDIT_LAYER_ID,
          preferredSourceId: getDefaultMapSourceIdFromStore(),
          minZoom: 1,
          maxZoom: 18
        });
        map.value = mapController.value.map;
        baseSourceOptions.value = mapController.value.getBaseSourceOptions();
        selectedBaseSourceId.value = mapController.value.getCurrentBaseSourceId();
      } catch {
        return;
      }
      updateMarkerFromCoords();

      map.value.on('click', (e) => {
        const {lng, lat} = e.lngLat;
        setCoords(lat, lng);
      });
    }

    async function loadPlaceForEdit(id) {
      loadingEdit.value = true;
      try {
        const res = await api.get('/features/' + id + '/');
        const f = res.data;
        name.value = (f.properties && f.properties.name) ? String(f.properties.name) : '';
        description.value = (f.properties && f.properties.description) ? String(f.properties.description) : '';
        const coords = f.geometry && f.geometry.coordinates;
        if (coords && coords.length >= 2) {
          const addressProp = f.properties && f.properties.address;
          if (addressProp) {
            setCoords(coords[1], coords[0], String(addressProp));
          } else {
            setCoords(coords[1], coords[0]);
          }
          if (map.value) {
            map.value.easeTo({
              center: [coords[0], coords[1]],
              zoom: 12,
              duration: 500
            });
          }
        }
        initialFormSnapshot.value = {
          name: name.value,
          description: description.value,
          lat: latitude.value,
          lon: longitude.value,
          address: storedAddress.value || null
        };
      } catch (err) {
        console.error('Failed to load place', err);
        toast.error(err.response?.data?.message || err.message || 'Failed to load place.');
        if (router) router.navigate('');
      } finally {
        loadingEdit.value = false;
      }
    }

    function handleSearchInput() {
      if (searchTimeout.value) clearTimeout(searchTimeout.value);
      if (!searchQuery.value.trim()) {
        searchResults.value = [];
        showResults.value = false;
        return;
      }
      showResults.value = true;
      searchTimeout.value = setTimeout(() => performSearch(), 300);
    }

    async function performSearch() {
      const query = searchQuery.value.trim();
      if (!query) return;
      showResults.value = true;
      currentSearchQuery.value = query;
      isSearching.value = true;
      try {
        const response = await fetch(`/api/geocoding/search/?q=${encodeURIComponent(query)}`, {credentials: 'include'});
        const data = await response.json();
        if (currentSearchQuery.value !== query) return;
        searchResults.value = data.data?.features ?? [];
      } catch (e) {
        console.error('Search failed', e);
        if (currentSearchQuery.value === query) searchResults.value = [];
      } finally {
        if (currentSearchQuery.value === query) {
          isSearching.value = false;
          searchTimeout.value = null;
        }
      }
    }

    function selectSearchResult(result) {
      showResults.value = false;
      searchResults.value = [];
      searchQuery.value = '';
      const coords = result.coordinates || result.center;
      if (coords && coords.length >= 2) {
        const [lon, lat] = coords;
        setCoords(lat, lon);
        if (map.value) {
          map.value.easeTo({
            center: [lon, lat],
            zoom: 12,
            duration: 500
          });
        }
      }
    }

    async function useCurrentLocation() {
      if (isGettingLocation.value) return;
      isGettingLocation.value = true;
      try {
        if (utils && typeof utils.checkGeolocationPermission === 'function') {
          const permission = await utils.checkGeolocationPermission();
          if (permission === 'denied') {
            toast.error('Location permission denied. Please enable it in your browser settings.');
            return;
          }
        }
        const getPos = utils && typeof utils.getCurrentPosition === 'function'
            ? utils.getCurrentPosition
            : () => new Promise((resolve, reject) => {
              if (!navigator.geolocation) {
                reject(new Error('Geolocation is not supported'));
                return;
              }
              navigator.geolocation.getCurrentPosition(
                  (pos) => resolve({latitude: pos.coords.latitude, longitude: pos.coords.longitude}),
                  reject,
                  {enableHighAccuracy: true, timeout: 10000, maximumAge: 0}
              );
            });
        const coords = await getPos();
        setCoords(coords.latitude, coords.longitude);
        if (map.value) {
          map.value.easeTo({
            center: [coords.longitude, coords.latitude],
            zoom: 14,
            duration: 500
          });
        }
      } catch (error) {
        console.error('Geolocation error:', error);
        if (error.code === 1) {
          toast.error('Location permission denied.');
        } else {
          toast.error('Failed to get your location.');
        }
      } finally {
        isGettingLocation.value = false;
      }
    }

    async function savePlace() {
      if (saving.value || !name.value.trim() || !coordinatesInput.value.trim()) return;
      let lat = latitude.value;
      let lng = longitude.value;
      if (lat == null || lng == null) {
        const input = coordinatesInput.value.trim();
        const parseCoordinates = window.gv_core?.GeoVault?.utils?.parseCoordinates;
        if (parseCoordinates) {
          const coordinates = parseCoordinates(input);
          if (coordinates) {
            lat = coordinates.lat;
            lng = coordinates.lng;
          }
        }
        if (lat == null || lng == null) {
          coordinateError.value = 'Invalid coordinates. Use the parse button for addresses.';
          return;
        }
      }
      saving.value = true;
      try {
        const properties = {
          name: name.value.trim(),
          description: (description.value || '').trim()
        };
        if (storedAddress.value) {
          properties.address = storedAddress.value;
        }
        const payload = {
          type: 'Feature',
          geometry: {
            type: 'Point',
            coordinates: [lng, lat]
          },
          properties
        };
        if (editId.value) {
          await api.put('/features/' + editId.value + '/', payload);
          toast.success('Place updated.');
        } else {
          await api.post('/features/', payload);
          toast.success('Place created.');
        }
        initialFormSnapshot.value = {
          name: name.value.trim(),
          description: (description.value || '').trim(),
          lat,
          lon: lng,
          address: storedAddress.value || null
        };
        if (router) router.navigate('');
      } catch (err) {
        console.error('Failed to save place', err);
        toast.error(err.response?.data?.message || err.message || 'Failed to save place.');
      } finally {
        saving.value = false;
      }
    }

    function goToList() {
      if (router) router.navigate('');
    }

    function resetMapViewport() {
      if (!map.value) return;
      const lat = latitude.value;
      const lon = longitude.value;
      const valid = lat != null && lon != null && isFinite(lat) && isFinite(lon);
      if (valid) {
        map.value.easeTo({
          center: [lon, lat],
          zoom: 12,
          bearing: 0,
          duration: 0
        });
        return;
      }
      map.value.easeTo({
        center: INITIAL_CENTER,
        zoom: INITIAL_ZOOM,
        bearing: 0,
        duration: 0
      });
    }

    function openLayerPickerModal() {
      showLayerPickerModal.value = true;
    }

    function closeLayerPickerModal() {
      showLayerPickerModal.value = false;
    }

    async function applyBaseSourceSelection() {
      if (!mapController.value) return;
      try {
        selectedBaseSourceId.value = await mapController.value.setBaseSource(selectedBaseSourceId.value);
        updateMarkerFromCoords();
      } catch {
        selectedBaseSourceId.value = mapController.value.getCurrentBaseSourceId();
      }
    }

    function handleBeforeUnload(e) {
      if (isDirty.value) {
        e.preventDefault();
        e.returnValue = '';
      }
    }

    onBeforeRouteLeave((_to, _from, next) => {
      if (!isDirty.value) {
        next();
        return;
      }
      if (confirm('You have unsaved changes. Leave anyway?')) {
        next();
      } else {
        next(false);
      }
    });

    function resetFormAndMap() {
      name.value = '';
      description.value = '';
      latitude.value = null;
      longitude.value = null;
      coordinatesInput.value = '';
      coordinateError.value = '';
      storedAddress.value = null;
      searchQuery.value = '';
      searchResults.value = [];
      showResults.value = false;
      currentSearchQuery.value = '';
      initialFormSnapshot.value = {name: '', description: '', lat: null, lon: null, address: null};
      if (searchTimeout.value != null) {
        clearTimeout(searchTimeout.value);
        searchTimeout.value = null;
      }
      if (addressSearchTimeout.value != null) {
        clearTimeout(addressSearchTimeout.value);
        addressSearchTimeout.value = null;
      }
      if (coordinatesValidationTimeout.value != null) {
        clearTimeout(coordinatesValidationTimeout.value);
        coordinatesValidationTimeout.value = null;
      }
      if (addressAbortController.value) {
        addressAbortController.value.abort();
        addressAbortController.value = null;
      }
      if (map.value) {
        updateMarkerFromCoords();
        map.value.easeTo({
          center: INITIAL_CENTER,
          zoom: INITIAL_ZOOM,
          bearing: 0,
          duration: 0
        });
      }
    }

    onMounted(() => {
      initMap();
      window.addEventListener('beforeunload', handleBeforeUnload);
      if (route.query.edit) {
        loadPlaceForEdit(parseInt(String(route.query.edit), 10));
      } else {
        resetFormAndMap();
      }
    });

    onBeforeUnmount(() => {
      window.removeEventListener('beforeunload', handleBeforeUnload);
      if (addressSearchTimeout.value != null) {
        clearTimeout(addressSearchTimeout.value);
        addressSearchTimeout.value = null;
      }
      if (coordinatesValidationTimeout.value != null) {
        clearTimeout(coordinatesValidationTimeout.value);
        coordinatesValidationTimeout.value = null;
      }
      if (addressAbortController.value) {
        addressAbortController.value.abort();
        addressAbortController.value = null;
      }
      if (mapController.value) {
        mapController.value.destroy();
        mapController.value = null;
        map.value = null;
      }
    });

    onDeactivated(() => {
      resetFormAndMap();
    });

    watch(() => route.query.edit, (newVal) => {
      if (!map.value) return;
      if (newVal != null && newVal !== '') {
        loadPlaceForEdit(parseInt(String(newVal), 10));
      } else {
        resetFormAndMap();
      }
    });

    return {
      mapContainer,
      showLayerPickerModal,
      baseSourceOptions,
      selectedBaseSourceId,
      editId,
      name,
      description,
      latitude,
      longitude,
      coordinatesInput,
      coordinateError,
      isGeocoding,
      saving,
      loadingEdit,
      isGettingLocation,
      searchQuery,
      searchResults,
      showResults,
      isSearching,
      searchTimeout,
      currentSearchQuery,
      handleSearchInput,
      performSearch,
      selectSearchResult,
      onCoordinatesInput,
      validateCoordinates,
      useCurrentLocation,
      savePlace,
      goToList,
      resetMapViewport,
      openLayerPickerModal,
      closeLayerPickerModal,
      applyBaseSourceSelection
    };
  }
};
</script>
