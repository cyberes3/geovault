<template>
  <div
      v-if="show"
      class="fixed inset-0 z-50"
      aria-labelledby="modal-title"
      role="dialog"
      aria-modal="true"
      @click="close"
  >
    <!-- Backdrop -->
    <div class="absolute inset-0 bg-black/50"></div>

    <!-- Modal Container -->
    <div class="absolute inset-0 flex items-stretch justify-stretch sm:items-center sm:justify-center">
      <div
          class="bg-white flex flex-col w-full h-full sm:h-[90vh] sm:max-w-3xl sm:rounded-lg shadow-xl"
          @click.stop
      >
        <!-- Modal Header (sticky) -->
        <div class="sticky top-0 z-10 flex items-center justify-between px-6 py-4 border-b border-gray-200 bg-gray-50">
          <h3 class="text-xl font-semibold text-gray-900" id="modal-title">Import Process Guide</h3>
          <button
              @click="close"
              class="text-gray-400 hover:text-gray-500 focus:outline-none focus:ring-2 focus:ring-blue-500 rounded-md p-1"
              aria-label="Close modal"
          >
            <XMarkIcon class="w-6 h-6" />
          </button>
        </div>

        <!-- Modal Content -->
        <div class="flex-1 overflow-y-auto px-6 py-4">
          <div class="prose prose-sm max-w-none space-y-6">
            <!-- Upload Process Section -->
            <section>
              <h4 class="text-lg font-semibold text-gray-900 mb-3">Upload Process</h4>
              <div class="space-y-2 text-gray-700">
                <p>To begin importing your geospatial data, you can upload files using one of these methods:</p>
                <ul class="list-disc list-inside space-y-1 ml-4">
                  <li><strong>Click to upload:</strong> Click anywhere in the upload area to open your file browser</li>
                  <li><strong>Drag and drop:</strong> Drag files directly from your computer onto the upload area</li>
                  <li><strong>Multiple files:</strong> You can select or drag multiple files at once for batch processing</li>
                </ul>
                <div class="mt-3 p-3 bg-blue-50 border border-blue-200 rounded-md">
                  <p class="text-sm"><strong>Supported formats:</strong> KML, KMZ, and GPX files</p>
                  <p class="text-sm"><strong>File size limit:</strong> Maximum 5MB per file</p>
                </div>
              </div>
            </section>

            <!-- Processing Workflow Section -->
            <section>
              <h4 class="text-lg font-semibold text-gray-900 mb-3">Processing Workflow</h4>
              <div class="space-y-4 text-gray-700">
                <p>Once you click "Upload", your files go through the following automated process:</p>

                <div class="space-y-4">
                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">1. File Validation</h5>
                    <p class="text-sm">The system validates your file format, checks security requirements, and verifies file size limits. Invalid files will be rejected with an error message.</p>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">2. Conversion to GeoJSON</h5>
                    <p class="text-sm">Your KML, KMZ, or GPX file is converted into GeoJSON format, which is the standard format used internally by the system.</p>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">3. Elevation Data Filling</h5>
                    <p class="text-sm">For lines and tracks (LineString and MultiLineString features), the system automatically fills in missing elevation data:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li><strong>Automatic detection:</strong> The system identifies points in lines and tracks that are missing elevation information</li>
                      <li><strong>Preservation:</strong> Existing elevation data is never overwritten - only missing values are filled</li>
                    </ul>
                    <div class="mt-2 p-3 bg-blue-50 border border-blue-200 rounded-md">
                      <p class="text-sm"><strong>Note:</strong> This process only applies to lines and tracks. Points and polygons are not modified. If elevation data filling fails or is disabled, your upload will still proceed successfully - only the elevation data will be missing.</p>
                    </div>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">4. Feature Extraction</h5>
                    <p class="text-sm">Geographic features (points, lines, polygons) are extracted from your file. Complex geometries may be split into simpler components for better processing.</p>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">5. Automatic Tag Generation</h5>
                    <p class="text-sm">Each feature automatically receives system-generated tags including:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li><strong>Type tags:</strong> Based on geometry type (point, line, polygon, etc.)</li>
                      <li><strong>Import date tags:</strong> Year and month of import (e.g., "import-2024", "import-january")</li>
                      <li><strong>Source file tag:</strong> The original filename is added as a tag</li>
                      <li><strong>Geocoding tags:</strong> Location-based tags (see Geocoding section below)</li>
                    </ul>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">6. Duplicate Detection</h5>
                    <p class="text-sm">The system performs comprehensive duplicate detection:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li><strong>Internal duplicates:</strong> Checks for duplicate features within the uploaded file itself</li>
                      <li><strong>Existing duplicates:</strong> Compares features against your existing feature library to identify duplicates</li>
                    </ul>
                    <p class="text-sm mt-2">Duplicate features are marked but not automatically removed - you can review and decide which ones to import during the review process.</p>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">7. Storage in Import Queue</h5>
                    <p class="text-sm">
                      Processed features are stored in your import queue, ready for review and final import. You can
                      see all uploaded files in the \"Ready to Import\" section below.
                    </p>
                  </div>
                </div>
              </div>
            </section>

            <!-- Geocoding Section -->
            <section>
              <h4 class="text-lg font-semibold text-gray-900 mb-3">Geocoding</h4>
              <div class="space-y-4 text-gray-700">
                <div class="p-4 bg-yellow-50 border border-yellow-200 rounded-md">
                  <p class="text-sm font-semibold text-yellow-900 mb-2">⚠️ Administrator Configuration Required</p>
                  <p class="text-sm">Geocoding must be enabled by your system administrator. If geocoding is not enabled, location-based tags will not be generated.</p>
                </div>

                <p>When enabled, geocoding automatically adds location-based tags to your features:</p>

                <div class="space-y-4">
                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">What Gets Geocoded?</h5>
                    <p class="text-sm">Geocoding is performed for:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li><strong>Points:</strong> All point features are geocoded</li>
                      <li><strong>Lines:</strong> LineString and MultiLineString features are geocoded</li>
                      <li><strong>Polygons:</strong> Polygon features are <strong>not</strong> geocoded</li>
                    </ul>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">How Geocoding Works</h5>
                    <p class="text-sm">The system uses external location services to determine location information based on the coordinates of your features.</p>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">Generated Tags</h5>
                    <p class="text-sm">Geocoding automatically generates tags such as:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li>City or town names</li>
                      <li>State or province names</li>
                      <li>Country codes</li>
                      <li>County or regional names</li>
                      <li>Proximity tags (e.g., "near [city name]" if within 5 miles)</li>
                      <li>Lake proximity tags (if within 1 mile of a lake)</li>
                    </ul>
                    <p class="text-sm mt-2">These tags help you organize and search your features by location.</p>
                  </div>
                </div>
              </div>
            </section>

            <!-- Review and Import Section -->
            <section>
              <h4 class="text-lg font-semibold text-gray-900 mb-3">Review and Import</h4>
              <div class="space-y-4 text-gray-700">
                <p>After your files are processed, you can review and import them:</p>

                <div class="space-y-4">
                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">Accessing the Import Queue</h5>
                    <p class="text-sm">All uploaded files appear in the "Ready to Import" section below. Each file shows:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li>Original filename</li>
                      <li>Number of features extracted</li>
                      <li>Processing status</li>
                      <li>Duplicate status (if applicable)</li>
                    </ul>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">Reviewing Features</h5>
                    <p class="text-sm">Click on any file in the queue to open the detailed review page where you can:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li>View all features on a map</li>
                      <li>Edit feature properties (name, description, tags)</li>
                      <li>Skip duplicate features</li>
                      <li>Review and modify custom icons</li>
                      <li>Save changes before importing</li>
                    </ul>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">Bulk Import</h5>
                    <p class="text-sm">You can select multiple files and import them all at once using the bulk import button. This is useful when you have many files that don't need individual review.</p>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">Final Import</h5>
                    <p class="text-sm">When you're ready, click "Import" to move features from the import queue into your permanent feature store. Once imported:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li>Features become part of your permanent library</li>
                      <li>They appear in your feature store and can be viewed on maps</li>
                      <li>They can be searched, filtered, and organized using tags</li>
                      <li>Imported features cannot be modified directly - you'll need to upload a replacement file if changes are needed</li>
                    </ul>
                  </div>
                </div>
              </div>
            </section>

            <!-- Tips Section -->
            <section>
              <h4 class="text-lg font-semibold text-gray-900 mb-3">Tips</h4>
              <div class="space-y-2 text-gray-700">
                <ul class="list-disc list-inside space-y-1 ml-4">
                  <li>Files with identical content or filenames are automatically detected as duplicates</li>
                  <li>Each file can only be imported once - duplicates are marked but not automatically removed</li>
                  <li>Large files may take longer to process - you can monitor progress in real-time</li>
                  <li>You can delete files from the import queue if you decide not to import them</li>
                  <li>Processing logs are available for each file to help troubleshoot any issues</li>
                </ul>
              </div>
            </section>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { XMarkIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'ImportHelpModal',
  components: {
    XMarkIcon
  },
  props: {
    show: {
      type: Boolean,
      required: true,
      default: false
    }
  },
  methods: {
    close() {
      this.$emit('close')
    },
    handleEscapeKey(event) {
      if (event.key === 'Escape' && this.show) {
        this.close()
      }
    }
  },
  mounted() {
    // Add keyboard event listener for Escape key to close modal
    document.addEventListener('keydown', this.handleEscapeKey)
  },
  beforeUnmount() {
    // Remove keyboard event listener
    document.removeEventListener('keydown', this.handleEscapeKey)
  },
  watch: {
    show(newVal) {
      // Prevent body scroll when modal is open
      if (newVal) {
        document.body.style.overflow = 'hidden'
        // Move modal to body to avoid parent container offsets
        this.$nextTick(() => {
          if (this.$el && this.$el.parentNode !== document.body) {
            document.body.appendChild(this.$el)
          }
        })
      } else {
        document.body.style.overflow = ''
      }
    }
  }
}
</script>

<style scoped>
</style>

