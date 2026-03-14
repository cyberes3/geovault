<template>
  <div class="h-full flex flex-col bg-gray-50">
    <!-- Map: 50% on mobile; flex-1 on desktop so form can fit-content -->
    <div class="h-1/2 min-h-[200px] sm:h-auto sm:flex-1 sm:min-h-0 flex-shrink-0 relative border-b border-gray-300">
      <div ref="mapContainer" class="absolute inset-0 bg-gray-100 touch-pan-y"></div>

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
    </div>

    <!-- Form: 50% scrollable on mobile; fit-content on desktop -->
    <div
        class="h-1/2 min-h-0 sm:h-auto sm:flex-shrink-0 flex flex-col bg-white border-t border-gray-300 shadow-[0_-2px_10px_rgba(0,0,0,0.05)]">
      <div class="flex-1 min-h-0 sm:flex-none sm:min-h-0 overflow-y-auto overscroll-contain">
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
  </div>
</template>

<script>
import {computed, inject, onBeforeUnmount, onDeactivated, onMounted, ref, watch} from 'vue';
import {onBeforeRouteLeave, useRoute} from 'vue-router';
import {ArrowPathIcon, MagnifyingGlassIcon, MapPinIcon} from '@heroicons/vue/24/outline';

export default {
  name: 'PlaceNewView',
  components: {
    ArrowPathIcon,
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
    const vectorSource = ref(null);

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
              map.value.getView().animate({
                center: window.gv_core.ol.proj.fromLonLat([lon, lat]),
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
      if (!vectorSource.value || !window.gv_core.ol) return;
      const lat = latitude.value;
      const lon = longitude.value;
      const valid = lat != null && lon != null && isFinite(lat) && isFinite(lon);
      vectorSource.value.clear();
      if (valid) {
        const feature = new window.gv_core.ol.Feature({
          geometry: new window.gv_core.ol.geom.Point(window.gv_core.ol.proj.fromLonLat([lon, lat]))
        });
        vectorSource.value.addFeatures([feature]);
        if (panMap && map.value) {
          map.value.getView().animate({
            center: window.gv_core.ol.proj.fromLonLat([lon, lat]),
            duration: 300
          });
        }
      }
    }


    function initMap() {
      if (!window.gv_core.ol || !mapContainer.value) return;
      vectorSource.value = new window.gv_core.ol.source.Vector();
      const vectorLayer = new window.gv_core.ol.layer.Vector({
        source: vectorSource.value,
        style: new window.gv_core.ol.style.Style({
          image: new window.gv_core.ol.style.Circle({
            radius: 7,
            fill: new window.gv_core.ol.style.Fill({color: '#2563EB'}),
            stroke: new window.gv_core.ol.style.Stroke({color: 'white', width: 2})
          })
        })
      });
      map.value = new window.gv_core.ol.Map({
        target: mapContainer.value,
        controls: [],
        layers: [
          new window.gv_core.ol.layer.Tile({
            source: new window.gv_core.ol.source.OSM({attributions: []})
          }),
          vectorLayer
        ],
        view: new window.gv_core.ol.View({
          center: window.gv_core.ol.proj.fromLonLat([0, 0]),
          zoom: 2
        })
      });
      map.value.on('click', (e) => {
        const lonLat = window.gv_core.ol.proj.toLonLat(e.coordinate);
        setCoords(lonLat[1], lonLat[0]);
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
            map.value.getView().animate({
              center: window.gv_core.ol.proj.fromLonLat([coords[0], coords[1]]),
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
          map.value.getView().animate({
            center: window.gv_core.ol.proj.fromLonLat([lon, lat]),
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
          map.value.getView().animate({
            center: window.gv_core.ol.proj.fromLonLat([coords.longitude, coords.latitude]),
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
      if (vectorSource.value) {
        vectorSource.value.clear();
      }
      if (map.value) {
        const view = map.value.getView();
        view.setCenter(window.gv_core.ol.proj.fromLonLat([0, 0]));
        view.setZoom(2);
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
      goToList
    };
  }
};
</script>
