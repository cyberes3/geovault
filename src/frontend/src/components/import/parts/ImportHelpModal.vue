<template>
  <BaseModal
    :is-open="show"
    title="Import Process Guide"
    max-width="3xl"
    @close="close"
  >
    <div class="px-6 py-4">
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
                <div class="mt-3 px-2 py-1 bg-blue-50 border border-blue-200 rounded-md">
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
                    <div class="mt-2 px-2 py-1 bg-blue-50 border border-blue-200 rounded-md">
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
                    <p class="text-sm">The system performs comprehensive duplicate detection using two methods:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li><strong>Exact Match (Hash-Based):</strong> Identifies features that are completely identical to ones you already have. These are always blocked and cannot be imported.</li>
                      <li><strong>Same Location (Geometry-Based):</strong> Identifies features at the exact same coordinates but with different properties. These are blocked by default but can be restored if needed.</li>
                    </ul>
                    <p class="text-sm mt-2">The system checks for duplicates in two places:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li><strong>Your Feature Library:</strong> Features already imported and visible on your map</li>
                      <li><strong>Your Import Table:</strong> Files uploaded before the current one to prevent importing the same feature multiple times</li>
                    </ul>
                    <p class="text-sm mt-2">Duplicate features are automatically marked and blocked. You can review them during the review process, and geometry duplicates (same location) can be restored if you want to import them anyway.</p>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">7. Storage in Import Table</h5>
                    <p class="text-sm">
                      Processed features are stored in your import table, ready for review and final import. You can
                      see all uploaded files in the "Ready to Import" section below.
                    </p>
                  </div>
                </div>
              </div>
            </section>

            <!-- Geocoding Section -->
            <section>
              <h4 class="text-lg font-semibold text-gray-900 mb-3">Geocoding</h4>
              <div class="space-y-4 text-gray-700">
                <div class="px-2 py-1 bg-yellow-50 border border-yellow-200 rounded-md">
                  <p class="text-sm text-yellow-900"><strong>⚠️ Administrator Configuration Required:</strong> Geocoding must be enabled by your system administrator. If not enabled, location-based tags will not be generated.</p>
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
                    <h5 class="text-base font-semibold text-gray-900 mb-2">Accessing the Import Table</h5>
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
                    <p class="text-sm">Click on any file in the table to open the detailed review page where you can:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li>View all features on a map</li>
                      <li>Edit feature properties (name, description, tags)</li>
                      <li>Skip duplicate features</li>
                      <li>Review and modify custom icons</li>
                      <li>Save changes before importing</li>
                    </ul>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">Importing from the Ready to Import Table</h5>
                    <p class="text-sm">The "Ready to Import" table provides two ways to import your processed files:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li><strong>Individual Import:</strong> Click on any file in the table to open the detailed review page where you can review, edit, and import features one file at a time</li>
                      <li><strong>Bulk Import:</strong> Select multiple files using the checkboxes and click the "Import" button to import all selected files at once without individual review. This is useful when you have many files that are ready to import as-is</li>
                    </ul>
                    <p class="text-sm mt-2">When using bulk import, the system will automatically skip duplicate features (both exact matches and same-location duplicates) that are already in your feature library or in other files being imported in the same batch.</p>
                  </div>

                  <div>
                    <h5 class="text-base font-semibold text-gray-900 mb-2">Final Import</h5>
                    <p class="text-sm">When you're ready, click "Import" to move features from the import table into your permanent feature store. Once imported:</p>
                    <ul class="list-disc list-inside ml-4 mt-1 text-sm">
                      <li>Features become part of your permanent library</li>
                      <li>They appear in your feature store and can be viewed on maps</li>
                      <li>They can be searched, filtered, and organized using tags</li>
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
                  <li>Each file can only be imported once</li>
                  <li>Large files may take longer to process - you can monitor progress in real-time</li>
                  <li>You can delete files from the import table if you decide not to import them</li>
                  <li>Processing logs are available for each file to help troubleshoot any issues</li>
                </ul>
              </div>
            </section>
      </div>
    </div>
  </BaseModal>
</template>

<script>
import BaseModal from '@/components/parts/BaseModal.vue'

export default {
  name: 'ImportHelpModal',
  components: {
    BaseModal
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
    }
  }
}
</script>

<style scoped>
</style>

