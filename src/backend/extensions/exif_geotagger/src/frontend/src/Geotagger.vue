<template>
  <div class="h-full flex flex-col p-6 bg-gray-50 overflow-y-auto font-sans">
    <div class="max-w-5xl mx-auto w-full space-y-6">
      <!-- Page Header (matches Collections) -->
      <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
        <div class="order-1">
          <h1 class="text-xl sm:text-2xl font-bold text-gray-900 mb-1 sm:mb-2">Photo Geotagger</h1>
        </div>
        <div class="mt-2 sm:mt-4 p-4 bg-blue-50 border border-blue-200 rounded-lg">
          <p class="text-sm text-gray-700">
            Add or update GPS location data in your photos.
          </p>
        </div>
      </div>

      <!-- Dropzone -->
      <div 
        @dragover.prevent="isDragging = true" 
        @dragleave.prevent="isDragging = false" 
        @drop.prevent="handleDrop"
        class="border-2 border-dashed rounded-xl cursor-pointer transition-all duration-200 h-[140px] flex items-center justify-center overflow-hidden bg-white"
        :class="isDragging ? 'border-blue-500 bg-blue-50 scale-[1.01]' : 'border-gray-300 hover:border-gray-400'"
        @click="$refs.fileInput.click()"
      >
        <input ref="fileInput" type="file" class="hidden" accept="image/jpeg,image/jpg" @change="handleFileSelect" />
        <div v-if="!imageFile" class="text-gray-500 flex flex-col items-center gap-3">
          <CloudArrowUpIcon class="w-10 h-10 text-gray-400" />
          <div>
              <p class="text-base font-medium text-gray-700">Drop photo here or click to upload</p>
              <p class="text-xs text-gray-500 mt-0.5">Supports JPEG/JPG</p>
          </div>
        </div>
        <div v-else class="flex items-center justify-center gap-5">
            <img :src="previewUrl" class="h-20 w-20 object-cover rounded-lg shadow-sm border border-gray-200" />
            <div class="text-left space-y-1">
                <p class="font-semibold text-gray-900 text-base leading-tight">{{ imageFile.name }}</p>
                <p class="text-xs text-gray-500">{{ formatSize(imageFile.size) }}</p>
                <button @click.stop="clearFile" class="text-red-500 text-xs hover:text-red-600 font-medium flex items-center gap-1 mt-1">
                    <TrashIcon class="w-3.5 h-3.5" />
                    Remove photo
                </button>
            </div>
        </div>
      </div>
      
      <!-- Instructions -->
      <div class="bg-white border border-gray-200 rounded-lg p-3 flex items-center gap-3 shadow-sm">
        <InformationCircleIcon class="w-5 h-5 text-blue-500" />
        <p class="text-sm font-medium text-gray-700">
          <span v-if="!imageFile">Upload a photo to start geotagging</span>
          <span v-else>Use the map to mark the coordinates for the photo</span>
        </p>
      </div>

      <!-- Map & Search -->
      <div class="relative bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
        
        <!-- Search Bar -->
         <div class="absolute top-4 left-4 right-4 z-10 max-w-md">
           <div class="bg-white ring-1 ring-black/5 flex items-center p-1" :class="searchResults.length ? 'rounded-t-lg' : 'rounded-lg'">
              <input 
                v-model="searchQuery" 
                @input="handleSearchInput"
                @keyup.enter="performSearch"
                placeholder="Search locations..." 
                class="flex-1 outline-none text-sm px-3 py-2 bg-transparent w-full disabled:cursor-not-allowed"
                :disabled="!imageFile"
              />
              <button @click="performSearch" :disabled="!imageFile" class="p-2 hover:bg-gray-100 rounded-md text-gray-500 transition-colors disabled:opacity-50 disabled:cursor-not-allowed">
                <div class="w-5 h-5 flex items-center justify-center overflow-hidden">
                  <Loader v-if="isSearching" size="sm" :show-message="false" class="!py-0 !mt-0" />
                  <MagnifyingGlassIcon v-else class="w-5 h-5" />
                </div>
              </button>
           </div>
           
           <!-- Search Results Dropdown -->
           <div v-if="showResults && (searchResults.length || (searchQuery && !isSearching && searchResults.length === 0 && searchTimeout === null))" class="bg-white ring-1 ring-black/5 max-h-60 overflow-y-auto w-full absolute top-full left-0 z-50">
              <div v-if="searchResults.length === 0" class="px-4 py-3 text-gray-500 text-sm italic">
                No results found
              </div>
              <div 
                v-else
                v-for="result in searchResults" 
                :key="result.id" 
                @click="selectResult(result)"
                class="px-4 py-2 hover:bg-gray-100 cursor-pointer border-b border-gray-100 last:border-0 transition-colors"
              >
                 <p class="text-sm font-semibold text-gray-900 truncate">{{ result.text || result.place_name }}</p>
                 <p v-if="result.place_name && result.place_name !== result.text" class="text-xs text-gray-500 truncate mt-0.5">{{ result.place_name }}</p>
              </div>
           </div>
        </div>

        <div ref="mapContainer" class="w-full h-[500px] bg-gray-100"></div>
      </div>

      <!-- Controls -->
      <div class="bg-white p-6 rounded-xl border border-gray-200 shadow-sm flex flex-col md:flex-row gap-6 items-end">
         <div class="flex-1 w-full space-y-1.5">
            <label class="text-xs font-semibold text-gray-500 uppercase tracking-wide">Latitude</label>
            <input v-model.number="lat" type="number" step="any" :disabled="!imageFile" class="w-full h-10 px-3 border border-gray-300 rounded-lg shadow-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 outline-none transition-all disabled:opacity-50 disabled:bg-gray-50 disabled:cursor-not-allowed" @input="updateMarkerFromInputs" placeholder="0.000000" />
         </div>
         <div class="flex-1 w-full space-y-1.5">
            <label class="text-xs font-semibold text-gray-500 uppercase tracking-wide">Longitude</label>
            <input v-model.number="lon" type="number" step="any" :disabled="!imageFile" class="w-full h-10 px-3 border border-gray-300 rounded-lg shadow-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 outline-none transition-all disabled:opacity-50 disabled:bg-gray-50 disabled:cursor-not-allowed" @input="updateMarkerFromInputs" placeholder="0.000000" />
         </div>
         <div class="flex-none w-full md:w-auto">
            <button 
                @click="downloadGeotagged" 
                :disabled="!imageFile || lat === null || lon === null"
                class="w-full md:w-auto bg-blue-600 text-white px-8 py-2.5 h-10 rounded-lg font-medium hover:bg-blue-700 disabled:opacity-50 disabled:bg-gray-400 disabled:cursor-not-allowed transition-all shadow-sm hover:shadow flex items-center justify-center gap-2"
            >
                <ArrowDownTrayIcon class="w-5 h-5" />
                Download Photo
            </button>
         </div>
      </div>
    </div>
  </div>
</template>

<script>
import 'ol/ol.css';
import { Map, View } from 'ol';
import { XYZ, Vector as VectorSource } from 'ol/source';
import { Tile as TileLayer, Vector as VectorLayer } from 'ol/layer';
import { fromLonLat, toLonLat } from 'ol/proj.js';
import Feature from 'ol/Feature.js';
import Point from 'ol/geom/Point.js';
import { Circle as CircleStyle, Fill, Stroke, Style } from 'ol/style.js';
import piexif from 'piexifjs';
import { 
  CloudArrowUpIcon, 
  TrashIcon, 
  MagnifyingGlassIcon, 
  ArrowDownTrayIcon,
  InformationCircleIcon
} from '@heroicons/vue/24/outline';

export default {
    name: 'Geotagger',
    components: {
      CloudArrowUpIcon,
      TrashIcon,
      MagnifyingGlassIcon,
      ArrowDownTrayIcon,
      InformationCircleIcon
    },
    data() {
        return {
            imageFile: null,
            previewUrl: null,
            isDragging: false,
            // Map
            map: null,
            markerSource: null,
            // Coords
            lat: null,
            lon: null,
            // Search
            searchQuery: '',
            searchResults: [],
            searchTimeout: null,
            isSearching: false,
            currentSearchQuery: '',
            showResults: false
        };
    },
    mounted() {
        this.initMap();
    },
    watch: {
        imageFile(newVal) {
            this.toggleMapInteractions(!!newVal);
        }
    },
    methods: {
        initMap() {
            this.markerSource = new VectorSource();
            const markerLayer = new VectorLayer({
                source: this.markerSource,
                style: new Style({
                    image: new CircleStyle({
                        radius: 7,
                        fill: new Fill({color: '#3b82f6'}),
                        stroke: new Stroke({color: 'white', width: 2})
                    })
                })
            });

            this.map = new Map({
                target: this.$refs.mapContainer,
                layers: [
                    new TileLayer({
                        source: new XYZ({
                            url: 'https://{a-c}.tile.openstreetmap.org/{z}/{x}/{y}.png',
                            crossOrigin: 'anonymous',
                            attributions: '© OpenStreetMap contributors'
                        })
                    }),
                    markerLayer
                ],
                controls: [],
                view: new View({
                    center: fromLonLat([0, 0]),
                    zoom: 2
                })
            });

            this.map.on('click', (e) => {
                if (!this.imageFile) return;
                const coords = toLonLat(e.coordinate);
                this.updateCoords(coords[1], coords[0]);
            });

            // Set initial interaction state
            this.toggleMapInteractions(!!this.imageFile);
        },
        toggleMapInteractions(enabled) {
            if (!this.map) return;
            this.map.getInteractions().forEach(interaction => {
                interaction.setActive(enabled);
            });
        },
        updateCoords(lat, lon) {
            this.lat = parseFloat(lat.toFixed(6));
            this.lon = parseFloat(lon.toFixed(6));
            
            this.markerSource.clear();
            const feature = new Feature({
                geometry: new Point(fromLonLat([this.lon, this.lat]))
            });
            this.markerSource.addFeature(feature);
        },
        updateMarkerFromInputs() {
            if (this.lat !== null && this.lon !== null && this.markerSource) {
                this.markerSource.clear();
                const feature = new Feature({
                    geometry: new Point(fromLonLat([this.lon, this.lat]))
                });
                this.markerSource.addFeature(feature);
            }
        },
        formatSize(bytes) {
            if (bytes === 0) return '0 Bytes';
            const k = 1024;
            const sizes = ['Bytes', 'KB', 'MB', 'GB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        },
        handleDrop(e) {
            this.isDragging = false;
            const file = e.dataTransfer.files[0];
            if (file) this.processFile(file);
        },
        handleFileSelect(e) {
            const file = e.target.files[0];
            if (file) this.processFile(file);
        },
        processFile(file) {
            if (!file.type.match('image/jpeg')) {
                alert('Only JPEG images are supported');
                return;
            }
            // Reset state for new upload
            this.lat = null;
            this.lon = null;
            this.searchQuery = '';
            this.searchResults = [];
            if (this.markerSource) this.markerSource.clear();

            this.imageFile = file;
            this.previewUrl = URL.createObjectURL(file);
            this.extractExifData(file);
        },
        clearFile() {
            this.imageFile = null;
            this.previewUrl = null;
            this.lat = null;
            this.lon = null;
            this.searchQuery = '';
            this.searchResults = [];
            if (this.markerSource) this.markerSource.clear();
            if (this.$refs.fileInput) this.$refs.fileInput.value = '';
        },
        handleSearchInput() {
            if (this.searchTimeout) {
                clearTimeout(this.searchTimeout);
            }
            if (!this.searchQuery.trim()) {
                this.searchResults = [];
                this.showResults = false;
                return;
            }
            this.showResults = true;
            this.searchTimeout = setTimeout(() => {
                this.performSearch();
            }, 300);
        },
        async performSearch() {
            const query = this.searchQuery.trim();
            if (!query) return;
            this.showResults = true;

            this.currentSearchQuery = query;
            this.isSearching = true;

            try {
                // Using the relative path as discovered in FeatureListSidebar
                const response = await fetch(`/api/geocoding/search/?q=${encodeURIComponent(query)}`, {
                    credentials: 'include'
                });
                const data = await response.json();
                
                // Only update if this is still the current query
                if (this.currentSearchQuery !== query) {
                    return;
                }

                // Response structure is { data: { features: [...] } }
                if (data.data && data.data.features) {
                    this.searchResults = data.data.features;
                } else if (data.features) {
                    this.searchResults = data.features;
                } else if (Array.isArray(data)) {
                    this.searchResults = data;
                } else {
                    this.searchResults = [];
                }
            } catch (e) {
                console.error("Search failed", e);
                if (this.currentSearchQuery === query) {
                    this.searchResults = [];
                }
            } finally {
                if (this.currentSearchQuery === query) {
                    this.isSearching = false;
                    this.searchTimeout = null;
                }
            }
        },
        selectResult(result) {
            this.showResults = false;
            this.searchResults = [];
            this.searchQuery = '';
            
            // Backend returns 'coordinates' ([lon, lat])
            const coords = result.coordinates || result.center;
            if (coords) {
                const [lon, lat] = coords;
                this.map.getView().animate({
                    center: fromLonLat([lon, lat]),
                    zoom: 12,
                    duration: 500
                });
            }
        },
        degToDms(deg) {
            const d = Math.floor(deg);
            const minFloat = (deg - d) * 60;
            const m = Math.floor(minFloat);
            const s = Math.round((minFloat - m) * 60 * 100) / 100;
            return [[d, 1], [m, 1], [Math.round(s * 100), 100]];
        },
        dmsToDeg(dms, ref) {
            if (!dms || dms.length < 3) return null;
            try {
                // Validate denominators to avoid NaN from 0/0
                const d_den = dms[0][1] || 0;
                const m_den = dms[1][1] || 0;
                const s_den = dms[2][1] || 0;
                
                if (d_den === 0 || m_den === 0 || s_den === 0) return null;

                const d = dms[0][0] / d_den;
                const m = dms[1][0] / m_den;
                const s = dms[2][0] / s_den;
                
                let deg = d + (m / 60) + (s / 3600);
                
                // Handle ref (piexif might return strings with null bytes)
                const r = String(ref || '').trim().replace(/\0/g, '').toUpperCase().charAt(0);
                if (r === 'S' || r === 'W') deg = -deg;
                
                return deg;
            } catch (e) {
                return null;
            }
        },
        extractExifData(file) {
            const reader = new FileReader();
            reader.onload = (e) => {
                const dataURL = e.target.result;
                try {
                    const exifObj = piexif.load(dataURL);
                    const gps = exifObj.GPS;
                    if (gps && gps[piexif.GPSIFD.GPSLatitude] && gps[piexif.GPSIFD.GPSLongitude]) {
                        const lat = this.dmsToDeg(gps[piexif.GPSIFD.GPSLatitude], gps[piexif.GPSIFD.GPSLatitudeRef]);
                        const lon = this.dmsToDeg(gps[piexif.GPSIFD.GPSLongitude], gps[piexif.GPSIFD.GPSLongitudeRef]);
                        
                        if (lat !== null && lon !== null && isFinite(lat) && isFinite(lon)) {
                            // Skip dummy 0,0 coordinates commonly found in uninitialized EXIF
                            if (lat === 0 && lon === 0) return;
                            
                            this.updateCoords(lat, lon);
                            if (this.map) {
                                this.map.getView().animate({
                                    center: fromLonLat([lon, lat]),
                                    zoom: 16,
                                    duration: 1000
                                });
                            }
                        }
                    }
                } catch (err) {
                    console.error('Error extracting EXIF data:', err);
                }
            };
            reader.readAsDataURL(file);
        },
        downloadGeotagged() {
            const reader = new FileReader();
            reader.onload = (e) => {
                const dataStr = e.target.result;
                let exifObj;
                try {
                    exifObj = piexif.load(dataStr);
                } catch (err) {
                    // Create empty exif if none exists
                    exifObj = { "0th": {}, "Exif": {}, "GPS": {}, "Interop": {}, "1st": {}, "thumbnail": null };
                }
                
                // Update GPS
                const latDms = this.degToDms(Math.abs(this.lat));
                const lonDms = this.degToDms(Math.abs(this.lon));
                
                const gps = {};
                gps[piexif.GPSIFD.GPSLatitudeRef] = this.lat < 0 ? 'S' : 'N';
                gps[piexif.GPSIFD.GPSLatitude] = latDms;
                gps[piexif.GPSIFD.GPSLongitudeRef] = this.lon < 0 ? 'W' : 'E';
                gps[piexif.GPSIFD.GPSLongitude] = lonDms;
                
                exifObj.GPS = gps;
                
                const exifBytes = piexif.dump(exifObj);
                const newJpeg = piexif.insert(exifBytes, dataStr);
                
                const link = document.createElement('a');
                link.href = newJpeg;
                // Add " -- geotagged" before extension
                const nameParts = this.imageFile.name.split('.');
                const ext = nameParts.pop();
                const basename = nameParts.join('.');
                link.download = `${basename} -- geotagged.${ext}`;
                link.click();
            };
            reader.readAsDataURL(this.imageFile);
        }
    }
}
</script>
