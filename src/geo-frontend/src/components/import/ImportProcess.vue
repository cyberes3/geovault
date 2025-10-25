<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold text-gray-900 mb-2">Process Import</h1>
          <h2 v-if="originalFilename != null" class="text-lg text-gray-600">{{ originalFilename }}</h2>
          <div v-else class="h-6 w-48 bg-gray-200 rounded animate-pulse"></div>
        </div>
        <div class="flex items-center space-x-2">
          <span v-if="isImported" class="inline-flex items-center px-3 py-1 rounded-full text-sm font-medium bg-green-100 text-green-800">
            <svg class="w-4 h-4 mr-1" fill="currentColor" viewBox="0 0 20 20">
              <path clip-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" fill-rule="evenodd"></path>
            </svg>
            Imported
          </span>
        </div>
      </div>
    </div>

    <!-- Error Message -->
    <div v-if="msg !== '' && msg != null" class="bg-red-50 border border-red-200 rounded-lg p-4">
      <div class="flex">
        <div class="flex-shrink-0">
          <svg class="h-5 w-5 text-red-400" fill="currentColor" viewBox="0 0 20 20">
            <path clip-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" fill-rule="evenodd"></path>
          </svg>
        </div>
        <div class="ml-3">
          <h3 class="text-sm font-medium text-red-800">Processing Failed</h3>
          <div class="mt-2 text-sm text-red-700">
            <p>{{ msg }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Import Logs -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-semibold text-gray-900">Processing Logs</h2>
        <button
            class="inline-flex items-center p-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            title="Open full log view"
            @click="dialogs.logs = true"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
          </svg>
        </button>
      </div>
      <div class="bg-gray-50 rounded-lg p-4">
        <div class="h-32 overflow-auto">
          <ul class="space-y-2">
            <li v-for="(item, index) in workerLog" :key="`logitem-${index}`"
                :class="{'bg-red-50 border-l-4 border-red-400 pl-2 py-1': item.level >= 40}"
                class="flex items-start space-x-2">
              <span class="text-sm text-gray-500">{{ formatTimestamp(item.timestamp) }}</span>
              <span v-if="item.source" class="text-xs text-gray-400 bg-gray-100 px-2 py-1 rounded">{{ item.source }}</span>
              <span
                  v-if="item.level !== undefined"
                  :class="getLevelClass(item.level)"
                  class="text-xs px-2 py-1 rounded font-medium"
              >
                {{ getLevelName(item.level) }}
              </span>
              <span :class="item.level >= 40 ? 'text-red-800 font-medium' : 'text-gray-700'" class="text-sm">{{ item.msg }}</span>
            </li>
            <li v-if="workerLog.length === 0" class="text-sm text-gray-500 italic">
              {{ loading.logs ? 'Fetching logs...' : 'No logs available yet...' }}
            </li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Import Summary -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h3 class="text-lg font-semibold text-gray-900 mb-4">Import Summary</h3>
      <div v-if="loading.page" class="text-center py-8">
        <span class="text-blue-600 font-medium">Loading...</span>
      </div>
      <div v-else class="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div class="bg-blue-50 border border-blue-200 rounded-lg p-4">
          <div class="flex items-center">
            <div class="flex-shrink-0">
              <svg class="h-8 w-8 text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
              </svg>
            </div>
            <div class="ml-3">
              <p class="text-sm font-medium text-blue-800">Total Features</p>
              <p class="text-2xl font-bold text-blue-900">{{ pagination.totalFeatures || itemsForUser.length }}</p>
            </div>
          </div>
        </div>

        <div class="bg-green-50 border border-green-200 rounded-lg p-4">
          <div class="flex items-center">
            <div class="flex-shrink-0">
              <svg class="h-8 w-8 text-green-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path d="M5 13l4 4L19 7" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
              </svg>
            </div>
            <div class="ml-3">
              <p class="text-sm font-medium text-green-800">Ready to Import</p>
              <p class="text-2xl font-bold text-green-900">{{ pagination.totalFeatures - duplicates.indices.length }}</p>
            </div>
          </div>
        </div>

        <div class="bg-yellow-50 border border-yellow-200 rounded-lg p-4">
          <div class="flex items-center">
            <div class="flex-shrink-0">
              <svg class="h-8 w-8 text-yellow-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L3.732 16.5c-.77.833.192 2.5 1.732 2.5z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
              </svg>
            </div>
            <div class="ml-3">
              <p class="text-sm font-medium text-yellow-800">Exact Duplicates</p>
              <p class="text-2xl font-bold text-yellow-900">{{ duplicates.indices.length }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Loading State for Initial Page Load -->
    <Loader v-if="originalFilename == null && !loading.page"/>

    <!-- Import Controls (Top) -->
    <ImportControls
        :current-page="pagination.currentPage"
        :duplicate-count="duplicates.indices.length"
        :goto-page-input="pagination.gotoInput"
        :has-features="itemsForUser.length > 0"
        :has-next-page="pagination.hasNext"
        :has-previous-page="pagination.hasPrevious"
        :importable-count="pagination.totalFeatures - duplicates.indices.length"
        :is-imported="isImported"
        :is-importing="loading.importing"
        :is-loading-page="loading.page"
        :is-saving="loading.saving"
        :lock-buttons="lockButtons"
        :page-size="pagination.pageSize"
        :show-no-features-message="originalFilename != null && !processing.active && !loading.page && itemsForUser.length === 0"
        :total-features="pagination.totalFeatures"
        :total-pages="pagination.totalPages"
        @previous-page="previousPage"
        @next-page="nextPage"
        @jump-to-page="goToPage"
        @show-map-preview="showMapPreview"
        @save-changes="saveChanges"
        @perform-import="performImport"
    />

    <!-- Loading Skeleton for Pagination Changes -->
    <div v-if="loading.page" class="space-y-6">
      <!-- Feature Item Skeletons -->
      <div v-for="i in Math.min(3, pagination.pageSize)" :key="`skeleton-${i}`" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6 animate-pulse">
        <div class="flex items-center justify-between mb-6">
          <div class="h-6 w-48 bg-gray-200 rounded"></div>
          <div class="flex items-center space-x-2">
            <div class="h-8 w-24 bg-gray-200 rounded"></div>
            <div class="h-6 w-16 bg-gray-200 rounded-full"></div>
          </div>
        </div>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div>
            <div class="h-4 w-16 bg-gray-200 rounded mb-2"></div>
            <div class="h-10 w-full bg-gray-200 rounded"></div>
          </div>
          <div class="md:col-span-2">
            <div class="h-4 w-24 bg-gray-200 rounded mb-2"></div>
            <div class="h-24 w-full bg-gray-200 rounded"></div>
          </div>
          <div>
            <div class="h-4 w-24 bg-gray-200 rounded mb-2"></div>
            <div class="h-10 w-full bg-gray-200 rounded"></div>
          </div>
          <div>
            <div class="h-4 w-12 bg-gray-200 rounded mb-2"></div>
            <div class="h-10 w-full bg-gray-200 rounded"></div>
          </div>
        </div>
      </div>

      <!-- Import Summary Skeleton -->
      <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6 animate-pulse">
        <div class="h-6 w-40 bg-gray-200 rounded mb-4"></div>
        <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div class="bg-gray-50 border border-gray-200 rounded-lg p-4">
            <div class="flex items-center">
              <div class="h-8 w-8 bg-gray-200 rounded mr-3"></div>
              <div class="flex-1 space-y-2">
                <div class="h-4 w-24 bg-gray-200 rounded"></div>
                <div class="h-8 w-16 bg-gray-200 rounded"></div>
              </div>
            </div>
          </div>
          <div class="bg-gray-50 border border-gray-200 rounded-lg p-4">
            <div class="flex items-center">
              <div class="h-8 w-8 bg-gray-200 rounded mr-3"></div>
              <div class="flex-1 space-y-2">
                <div class="h-4 w-24 bg-gray-200 rounded"></div>
                <div class="h-8 w-16 bg-gray-200 rounded"></div>
              </div>
            </div>
          </div>
          <div class="bg-gray-50 border border-gray-200 rounded-lg p-4">
            <div class="flex items-center">
              <div class="h-8 w-8 bg-gray-200 rounded mr-3"></div>
              <div class="flex-1 space-y-2">
                <div class="h-4 w-32 bg-gray-200 rounded"></div>
                <div class="h-8 w-16 bg-gray-200 rounded"></div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Action Buttons Skeleton -->
      <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6 animate-pulse">
        <div class="flex items-center space-x-4">
          <div class="h-10 w-32 bg-gray-200 rounded"></div>
          <div class="h-10 w-40 bg-gray-200 rounded"></div>
        </div>
      </div>
    </div>

    <!-- Processing Status -->
    <div v-if="processing.active" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-center py-8">
        <div class="text-center">
          <div class="animate-spin h-8 w-8 border-2 border-blue-600 border-t-transparent rounded-full mx-auto mb-4"></div>
          <h3 class="text-lg font-medium text-gray-900 mb-2">Processing File</h3>
          <p class="text-gray-600">{{ processing.message }}</p>
          <div v-if="processing.progress !== null" class="mt-4">
            <div class="w-full bg-gray-200 rounded-full h-2">
              <div :style="{ width: processing.progress + '%' }" class="bg-blue-600 h-2 rounded-full transition-all duration-300"></div>
            </div>
            <p class="text-sm text-gray-500 mt-2">{{ Math.round(processing.progress) }}% complete</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Feature Items -->
    <div v-else-if="itemsForUser.length > 0 && !loading.page" class="space-y-6">
      <div v-for="(item, index) in itemsForUser" :key="`item-${index}`"
           :class="item.isDuplicate ? 'bg-gray-100 rounded-lg shadow-sm border border-gray-300 p-6 opacity-75' : 'bg-white rounded-lg shadow-sm border border-gray-200 p-6'">
        <div class="flex items-center justify-between mb-6">
          <h3 class="text-lg font-semibold text-gray-900">Feature {{ (pagination.currentPage - 1) * pagination.pageSize + index + 1 }} (of {{ pagination.totalFeatures }})</h3>
          <div class="flex items-center space-x-2">
            <button
                class="inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                @click="showFeatureMap(index)"
            >
              <svg class="w-3 h-3 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
              </svg>
              View on Map
            </button>
            <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
              {{ item.geometry.type }}
            </span>
          </div>
        </div>

        <!-- Duplicate Warning -->
        <div v-if="item.isDuplicate" class="mb-4 p-4 bg-yellow-50 border border-yellow-200 rounded-md">
          <div class="flex items-start">
            <div class="flex-shrink-0">
              <svg class="h-5 w-5 text-yellow-400" fill="currentColor" viewBox="0 0 20 20">
                <path clip-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" fill-rule="evenodd"></path>
              </svg>
            </div>
            <div class="ml-3 flex-1">
              <h3 class="text-sm font-medium text-yellow-800">Duplicate Feature Detected</h3>
              <div class="mt-2 text-sm text-yellow-700">
                <p>This feature has the same coordinates as an existing feature in your feature store.</p>
                <div class="mt-2">
                  <button
                      class="inline-flex items-center px-3 py-1.5 border border-transparent text-xs font-medium rounded-md text-white bg-yellow-600 hover:bg-yellow-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-yellow-500"
                      @click="editOriginalFeature(item.duplicateInfo)"
                  >
                    <svg class="w-3 h-3 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                    </svg>
                    Edit Original Feature
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
          <!-- Name Field -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">Name</label>
            <div class="flex items-center space-x-2">
              <input
                  v-model="item.properties.name"
                  :class="isImported || item.isDuplicate || loading.importing ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500'"
                  :disabled="isImported || item.isDuplicate || loading.importing"
                  :placeholder="originalItems[index].properties.name"
              />
              <button
                  v-if="!isImported && !item.isDuplicate && !loading.importing"
                  class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                  @click="resetNestedField(index, 'properties', 'name')"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
              </button>
            </div>
          </div>

          <!-- Description Field -->
          <div class="md:col-span-2">
            <label class="block text-sm font-medium text-gray-700 mb-2">Description</label>
            <div class="flex items-start space-x-2">
              <textarea
                  v-model="item.properties.description"
                  :class="isImported || item.isDuplicate || loading.importing ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed resize-none' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 resize-none'"
                  :disabled="isImported || item.isDuplicate || loading.importing"
                  :placeholder="originalItems[index].properties.description"
                  class="text-sm"
                  rows="4"
              ></textarea>
              <button
                  v-if="!isImported && !item.isDuplicate && !loading.importing"
                  class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 mt-1"
                  @click="resetNestedField(index, 'properties', 'description')"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
              </button>
            </div>
          </div>

          <!-- Created Date Field -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">Created Date</label>
            <div class="flex items-center space-x-2">
              <input
                  :class="isImported || item.isDuplicate || loading.importing ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500'"
                  :disabled="isImported || item.isDuplicate || loading.importing"
                  :value="formatDateForInput(item.properties.created)"
                  type="datetime-local"
                  @change="updateDate(index, $event)"
              />
              <button
                  v-if="!isImported && !item.isDuplicate && !loading.importing"
                  class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                  @click="resetNestedField(index, 'properties', 'created')"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
              </button>
            </div>
          </div>

          <!-- Tags Section -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">Tags</label>
            <div class="space-y-2">
              <div v-for="(tag, tagIndex) in item.properties.tags" :key="`tag-${tagIndex}`" class="flex items-center space-x-2">
                <input
                    v-model="item.properties.tags[tagIndex]"
                    :class="isImported || item.isDuplicate || loading.importing ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500'"
                    :disabled="isImported || item.isDuplicate || loading.importing"
                    :placeholder="getTagPlaceholder(index, tag)"
                />
                <button
                    v-if="!isImported && !item.isDuplicate && !loading.importing"
                    class="inline-flex items-center px-3 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500"
                    @click="removeTag(index, tagIndex)"
                >
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                  </svg>
                </button>
              </div>
            </div>
            <div v-if="!isImported && !item.isDuplicate && !loading.importing" class="flex items-center space-x-2 mt-3">
              <button
                  :class="{ 'opacity-50 cursor-not-allowed': isLastTagEmpty(index) }"
                  :disabled="isLastTagEmpty(index)"
                  class="inline-flex items-center px-3 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:bg-gray-400"
                  @click="addTag(index)"
              >
                <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M12 6v6m0 0v6m0-6h6m-6 0H6" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
                Add Tag
              </button>
              <button
                  class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                  @click="resetTags(index)"
              >
                <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
                Reset Tags
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Import Controls (Bottom) -->
    <ImportControls
        v-if="!loading.page"
        :current-page="pagination.currentPage"
        :duplicate-count="duplicates.indices.length"
        :goto-page-input="pagination.gotoInput"
        :has-features="itemsForUser.length > 0"
        :has-next-page="pagination.hasNext"
        :has-previous-page="pagination.hasPrevious"
        :importable-count="pagination.totalFeatures - duplicates.indices.length"
        :is-imported="isImported"
        :is-importing="loading.importing"
        :is-loading-page="loading.page"
        :is-saving="loading.saving"
        :lock-buttons="lockButtons"
        :page-size="pagination.pageSize"
        :show-duplicate-message="false"
        :show-no-features-message="false"
        :total-features="pagination.totalFeatures"
        :total-pages="pagination.totalPages"
        @previous-page="previousPage"
        @next-page="nextPage"
        @jump-to-page="goToPage"
        @show-map-preview="showMapPreview"
        @save-changes="saveChanges"
        @perform-import="performImport"
    />

    <div class="hidden">
      <!-- Load the queue to populate it. -->
      <Importqueue/>
    </div>

    <!-- Map Preview Dialog -->
    <MapPreviewDialog
        :features="itemsForUser"
        :filename="originalFilename"
        :is-open="dialogs.mapPreview"
        @close="closeMapPreview"
    />

    <!-- Feature Map Dialog -->
    <FeatureMapDialog
        :features="itemsForUser"
        :filename="originalFilename"
        :is-open="dialogs.featureMap.isOpen"
        :selected-feature-index="dialogs.featureMap.selectedIndex"
        @close="closeFeatureMap"
    />

    <!-- Edit Original Feature Dialog -->
    <EditOriginalFeatureDialog
        :is-open="dialogs.editOriginal.isOpen"
        :loading="dialogs.editOriginal.loading"
        :original-feature="dialogs.editOriginal.feature"
        @close="closeEditOriginalDialog"
        @saved="onOriginalFeatureSaved"
    />

    <!-- Log View Modal -->
    <LogViewModal
        :is-open="dialogs.logs"
        :logs="workerLog"
        @close="closeLogModal"
    />
  </div>
</template>

<script>
import {mapState} from "vuex";
import {authMixin} from "@/assets/js/authMixin.js";
import axios from "axios";
import moment from "moment";
import {capitalizeFirstLetter} from "@/assets/js/string.js";
import ImportQueue from "@/components/import/parts/ImportQueue.vue";
import {GeoFeatureTypeStrings} from "@/assets/js/types/geofeature-strings";
import {GeoPoint, GeoLineString, GeoPolygon} from "@/assets/js/types/geofeature-types";
import {getCookie} from "@/assets/js/auth.js";
// Removed flatpickr dependency - using native HTML5 date input
import Loader from "@/components/parts/Loader.vue";
import MapPreviewDialog from "@/components/import/parts/MapPreviewDialog.vue";
import FeatureMapDialog from "@/components/import/parts/FeatureMapDialog.vue";
import EditOriginalFeatureDialog from "@/components/import/parts/EditOriginalFeatureDialog.vue";
import LogViewModal from "@/components/import/parts/LogViewModal.vue";
import ImportControls from "@/components/import/parts/ImportControls.vue";

// TODO: for each feature, query the DB and check if there is a duplicate. For points that's duplicate coords, for linestrings and polygons that's duplicate points
// TODO: redo the entire log feature to include local timestamps

export default {
  computed: {
    ...mapState(["userInfo"]),
    isValidPageNumber() {
      return this.pagination.gotoInput &&
          this.pagination.gotoInput >= 1 &&
          this.pagination.gotoInput <= this.pagination.totalPages &&
          this.pagination.gotoInput !== this.pagination.currentPage;
    }
  },
  components: {Loader, Importqueue: ImportQueue, MapPreviewDialog, FeatureMapDialog, EditOriginalFeatureDialog, LogViewModal, ImportControls},
  data() {
    return {
      // Core data
      msg: "",
      currentId: null,
      originalFilename: null,
      itemsForUser: [],
      originalItems: [],
      workerLog: [],

      // Consolidated: Dialog state
      dialogs: {
        mapPreview: false,
        featureMap: {isOpen: false, selectedIndex: 0},
        editOriginal: {isOpen: false, feature: null, loading: false},
        logs: false
      },

      // Consolidated: Loading states
      loading: {
        logs: true,
        page: false,
        saving: false,
        importing: false,
        redirecting: false
      },

      // Consolidated: Processing state
      processing: {
        active: false,
        message: '',
        progress: null,
        pollingInterval: null
      },

      // Log tracking for incremental updates
      lastLogId: null,

      // Consolidated: Pagination
      pagination: {
        currentPage: 1,
        pageSize: 50,
        totalFeatures: 0,
        totalPages: 0,
        hasNext: false,
        hasPrevious: false,
        gotoInput: null
      },

      // Consolidated: Duplicates
      duplicates: {
        features: [],
        indices: []
      },

      // Consolidated: Edit cache
      editCache: {
        pages: {},
        originals: {}
      },

      // Misc state
      lockButtons: false,
      isImported: false
    }
  },
  beforeDestroy() {
    // Clean up polling interval
    this.stopProcessingPolling()
  },
  mixins: [authMixin],
  props: ['id'],
  methods: {
    async checkProcessingStatus() {
      // Safety check: don't make API calls if currentId is null (component is being destroyed)
      if (!this.currentId) {
        return;
      }

      try {
        const response = await axios.get(`/api/data/item/import/get/${this.currentId}?page=1&page_size=${this.pagination.pageSize}`)
        if (response.data.success) {
          this.processing.active = response.data.processing
          if (this.processing.active && response.data.job_details) {
            this.processing.message = response.data.job_details.message || 'Processing file...'
            this.processing.progress = response.data.job_details.progress || 0

            // Fetch new logs during processing for real-time updates
            await this.loadLogsIncremental()
          } else if (!this.processing.active) {
            // Processing completed, refresh the page data
            this.stopProcessingPolling()
            await this.refreshImportItem()
          }
        }
      } catch (error) {
        console.error('Failed to check processing status:', error)
      }
    },
    async refreshImportItem() {
      // Refresh the import item data after processing completes
      try {
        // Fetch items and logs in parallel for better performance
        const [itemsResponse, logsResponse] = await Promise.all([
          axios.get(`/api/data/item/import/get/${this.currentId}?page=1&page_size=${this.pagination.pageSize}`),
          axios.get(`/api/data/item/import/logs/${this.currentId}`)
        ])

        if (itemsResponse.data.success) {
          // Load logs first (they're already fetched)
          if (logsResponse.data && logsResponse.data.logs) {
            this.workerLog = logsResponse.data.logs || []
          }
          this.processing.active = itemsResponse.data.processing || false

          if (Object.keys(itemsResponse.data).length > 0) {
            this.originalFilename = itemsResponse.data.original_filename
            this.isImported = itemsResponse.data.imported || false

            // Update pagination info
            if (itemsResponse.data.pagination) {
              this.pagination.currentPage = itemsResponse.data.pagination.page;
              this.pagination.totalFeatures = itemsResponse.data.pagination.total_features;
              this.pagination.totalPages = itemsResponse.data.pagination.total_pages;
              this.pagination.hasNext = itemsResponse.data.pagination.has_next;
              this.pagination.hasPrevious = itemsResponse.data.pagination.has_previous;
              this.duplicates.indices = itemsResponse.data.pagination.duplicate_indices || [];
            }


            if (itemsResponse.data.geofeatures.length > 0 && itemsResponse.data.geofeatures[0].error) {
              // Check if this is an error response (unprocessable file)
              // This is an unprocessable file, show a simple error message
              const errorItem = itemsResponse.data.geofeatures[0];

              // Extract error message from logs if available
              const errorLogs = this.workerLog.filter(log => log.level >= 40); // ERROR or CRITICAL
              if (errorLogs.length > 0) {
                // Use the most recent error message from logs
                const latestError = errorLogs[errorLogs.length - 1];
                this.msg = latestError.msg || "File processing failed. Please check the processing logs below for details.";
              } else {
                this.msg = errorItem.message || "File processing failed. Please check the processing logs below for details.";
              }

              // Keep the logs we already fetched, but add the error message if not already present
              if (this.workerLog.length === 0) {
                this.workerLog = [{timestamp: new Date().toISOString(), msg: this.msg}];
              }
            } else {
              // Normal processing - parse the geofeatures
              this.itemsForUser = []
              itemsResponse.data.geofeatures.forEach((item) => {
                this.itemsForUser.push(this.parseGeoJson(item))
              })
              this.originalItems = JSON.parse(JSON.stringify(this.itemsForUser))

              // Process duplicates from the API response
              this.duplicates.features = itemsResponse.data.duplicates || []
              this.markDuplicateFeatures()
            }
          }
        }
      } catch (error) {
        console.error('Error refreshing import item:', error)
      }
    },
    startProcessingPolling() {
      this.processing.pollingInterval = setInterval(() => {
        this.checkProcessingStatus()
      }, 2000) // Poll every 2 seconds
    },
    stopProcessingPolling() {
      if (this.processing.pollingInterval) {
        clearInterval(this.processing.pollingInterval)
        this.processing.pollingInterval = null
      }
    },
    getLevelName(level) {
      const levelMap = {
        10: 'DEBUG',
        20: 'INFO',
        30: 'WARNING',
        40: 'ERROR',
        50: 'CRITICAL'
      };
      return levelMap[level] || 'UNKNOWN';
    },
    getLevelClass(level) {
      if (level >= 40) { // ERROR or CRITICAL
        return 'bg-red-100 text-red-800';
      } else if (level >= 30) { // WARNING
        return 'bg-yellow-100 text-yellow-800';
      } else if (level >= 20) { // INFO
        return 'bg-blue-100 text-blue-800';
      } else { // DEBUG
        return 'bg-gray-100 text-gray-800';
      }
    },
    formatTimestamp(timestamp) {
      if (!timestamp) return '';
      return moment(timestamp).format('YYYY-MM-DD HH:mm:ss');
    },
    parseGeoJson(item) {
      switch (item.geometry.type) {
        case GeoFeatureTypeStrings.Point:
        case GeoFeatureTypeStrings.MultiPoint:
          return new GeoPoint(item);
        case GeoFeatureTypeStrings.LineString:
        case GeoFeatureTypeStrings.MultiLineString:
          return new GeoLineString(item);
        case GeoFeatureTypeStrings.Polygon:
        case GeoFeatureTypeStrings.MultiPolygon:
          return new GeoPolygon(item);
        default:
          throw new Error(`Invalid feature type: ${item.geometry.type}`);
      }
    },
    resetField(index, fieldName) {
      this.itemsForUser[index][fieldName] = this.originalItems[index][fieldName];
    },
    resetNestedField(index, nestedField, fieldName) {
      this.itemsForUser[index][nestedField][fieldName] = this.originalItems[index][nestedField][fieldName];
    },
    addTag(index) {
      if (!this.isLastTagEmpty(index)) {
        this.itemsForUser[index].properties.tags.push('');
      }
    },
    getTagPlaceholder(index, tag) {
      const originalTagIndex = this.originalItems[index].properties.tags.indexOf(tag);
      return originalTagIndex !== -1 ? this.originalItems[index].properties.tags[originalTagIndex] : '';
    },
    isLastTagEmpty(index) {
      const tags = this.itemsForUser[index].properties.tags;
      return tags.length > 0 && tags[tags.length - 1].trim().length === 0;
    },
    resetTags(index) {
      this.itemsForUser[index].properties.tags = [...this.originalItems[index].properties.tags];
    },
    removeTag(index, tagIndex) {
      this.itemsForUser[index].properties.tags.splice(tagIndex, 1);
    },
    updateDate(index, event) {
      this.itemsForUser[index].properties.created = event.target.value;
    },
    formatDateForInput(dateString) {
      if (!dateString) return '';
      // Convert date string to datetime-local format (YYYY-MM-DDTHH:MM)
      const date = new Date(dateString);
      if (isNaN(date.getTime())) return '';
      return date.toISOString().slice(0, 16);
    },
    async _saveChangesInternal() {
      // Internal save function that doesn't manage locks
      // This can be called by both saveChanges() and performImport()

      // Cache current page changes first
      this.cacheCurrentPageChanges();

      // Collect only changed features from current page and cached pages
      const changedFeatures = [];

      // Helper function to get comparable feature data (excluding UI-only properties)
      const getComparableFeature = (feature) => {
        return {
          type: feature.type,
          geometry: feature.geometry,
          properties: feature.properties
        };
      };

      // Helper function to check if a feature has changed
      const hasChanged = (current, original) => {
        const currentComparable = getComparableFeature(current);
        const originalComparable = getComparableFeature(original);
        return JSON.stringify(currentComparable) !== JSON.stringify(originalComparable);
      };

      // Check current page for changes
      this.itemsForUser.forEach((feature, idx) => {
        if (!feature.isDuplicate && hasChanged(feature, this.originalItems[idx])) {
          // Only include features that have changed (but send the full feature with ID)
          changedFeatures.push(getComparableFeature(feature));
        }
      });

      // Check cached pages for changes
      Object.entries(this.editCache.pages).forEach(([page, cachedFeatures]) => {
        const pageNum = parseInt(page);
        if (pageNum !== this.pagination.currentPage) {
          const originalForPage = this.editCache.originals[pageNum] || [];
          cachedFeatures.forEach((feature, idx) => {
            const globalIdx = (pageNum - 1) * this.pagination.pageSize + idx;
            // Skip duplicates
            if (!this.duplicates.indices.includes(globalIdx) && !feature.isDuplicate) {
              // Compare with original if we have it
              const original = originalForPage[idx];
              if (!original || hasChanged(feature, original)) {
                changedFeatures.push(getComparableFeature(feature));
              }
            }
          });
        }
      });

      if (changedFeatures.length === 0) {
        // No changes to save
        return {success: true, changedCount: 0};
      }

      const csrftoken = getCookie('csrftoken');

      // Save only changed features using the new API format
      const response = await axios.put('/api/data/item/import/update/' + this.currentId, {
        features: changedFeatures
      }, {
        headers: {
          'X-CSRFToken': csrftoken
        }
      });

      if (response.data.success) {
        // Update original items to reflect saved state for current page
        this.itemsForUser.forEach((feature, idx) => {
          this.originalItems[idx] = JSON.parse(JSON.stringify(feature));
        });

        // Also update the cached original items for current page
        if (this.pagination.currentPage) {
          this.editCache.originals[this.pagination.currentPage] = JSON.parse(JSON.stringify(this.originalItems));
        }

        // For cached pages, update their original state to match the current state
        // since we just saved those changes
        Object.keys(this.editCache.pages).forEach(page => {
          const pageNum = parseInt(page);
          if (pageNum !== this.pagination.currentPage) {
            // Update original to match current since we saved
            this.editCache.originals[pageNum] = JSON.parse(JSON.stringify(this.editCache.pages[pageNum]));
          }
        });

        // Show success message
        if (response.data.updated_count > 0) {
          console.log(`Successfully saved ${response.data.updated_count} feature(s)`);
        }

        return {success: true, changedCount: response.data.updated_count};
      } else {
        throw new Error(response.data.msg);
      }
    },
    async saveChanges() {
      // User-facing save function that manages locks and error handling
      this.lockButtons = true;
      this.loading.saving = true;

      try {
        await this._saveChangesInternal();
      } catch (error) {
        this.msg = 'Error saving changes: ' + (error.response?.data?.msg || error.message);
        window.alert(this.msg);
      } finally {
        this.lockButtons = false;
        this.loading.saving = false;
      }
    },
    async performImport() {
      this.lockButtons = true;
      this.loading.importing = true;
      const csrftoken = getCookie('csrftoken');

      try {
        // Save any pending changes first before importing
        try {
          const saveResult = await this._saveChangesInternal();
          if (saveResult.changedCount > 0) {
            console.log(`Saved ${saveResult.changedCount} change(s) before import`);
          }
        } catch (saveError) {
          this.msg = 'Error saving changes before import: ' + (saveError.response?.data?.msg || saveError.message);
          window.alert(this.msg);
          return; // Don't proceed with import if save fails
        }

        // Perform the import - server will use the stored features in the database
        // No need to send the feature collection, it's already saved
        const response = await axios.post('/api/data/item/import/perform/' + this.currentId, {}, {
          headers: {
            'X-CSRFToken': csrftoken
          }
        });

        if (response.data.success) {
          this.$store.dispatch('refreshImportQueue');
          // Remove the beforeunload handler before redirecting
          if (this.beforeUnloadHandler) {
            window.removeEventListener('beforeunload', this.beforeUnloadHandler);
          }
          // Redirect to import page after successful import
          this.loading.redirecting = true;
          window.alert('Import successful: ' + response.data.msg);
          this.$router.replace('/import');
        } else {
          this.msg = 'Error performing import: ' + response.data.msg;
          window.alert(this.msg);
        }
      } catch (error) {
        this.msg = 'Error performing import: ' + (error.response?.data?.msg || error.message);
        window.alert(this.msg);
      } finally {
        this.lockButtons = false;
        this.loading.importing = false;
      }
    },
    showMapPreview() {
      this.dialogs.mapPreview = true;
    },
    closeMapPreview() {
      this.dialogs.mapPreview = false;
    },
    showFeatureMap(featureIndex) {
      this.dialogs.featureMap.selectedIndex = featureIndex;
      this.dialogs.featureMap.isOpen = true;
    },
    closeFeatureMap() {
      this.dialogs.featureMap.isOpen = false;
    },
    markDuplicateFeatures() {
      // Reset all features to not be duplicates
      this.itemsForUser.forEach((item, index) => {
        item.isDuplicate = false;
        item.duplicateInfo = null;
      });

      // Mark duplicate features using the page_index from the new API format
      // The backend now provides page_index which directly tells us which feature on the current page is a duplicate
      this.duplicates.features.forEach(duplicateInfo => {
        const pageIndex = duplicateInfo.page_index;
        if (pageIndex >= 0 && pageIndex < this.itemsForUser.length) {
          this.itemsForUser[pageIndex].isDuplicate = true;
          this.itemsForUser[pageIndex].duplicateInfo = duplicateInfo;
        }
      });
    },
    async editOriginalFeature(duplicateInfo) {
      // Fetch the full feature data since the new API format only provides basic fields
      const existingFeature = duplicateInfo.existing_features[0];

      // Show dialog with loading state immediately
      this.dialogs.editOriginal.isOpen = true;
      this.dialogs.editOriginal.loading = true;
      this.dialogs.editOriginal.feature = null;

      try {
        const response = await axios.get(`/api/data/feature/${existingFeature.id}/`);
        if (response.data.success) {
          this.dialogs.editOriginal.feature = response.data.feature;
        } else {
          console.error('Failed to fetch feature data:', response.data.msg);
          alert('Error loading feature data: ' + response.data.msg);
          this.dialogs.editOriginal.isOpen = false;
        }
      } catch (error) {
        console.error('Error fetching feature data:', error);
        alert('Error loading feature data: ' + error.message);
        this.dialogs.editOriginal.isOpen = false;
      } finally {
        this.dialogs.editOriginal.loading = false;
      }
    },
    closeEditOriginalDialog() {
      this.dialogs.editOriginal.isOpen = false;
      this.dialogs.editOriginal.feature = null;
      this.dialogs.editOriginal.loading = false;
    },
    closeLogModal() {
      this.dialogs.logs = false;
    },
    async onOriginalFeatureSaved(featureId) {
      // Handle when the original feature is saved
      console.log(`Original feature ${featureId} was updated`);

      // Refresh the original feature data to reflect the changes
      try {
        const response = await axios.get(`/api/data/feature/${featureId}/`);
        if (response.data.success) {
          // Update the selectedOriginalFeature with the fresh data
          this.dialogs.editOriginal.feature = response.data.feature;

          // Also update the duplicate info in the itemsForUser array
          this.itemsForUser.forEach((item, index) => {
            if (item.isDuplicate && item.duplicateInfo &&
                item.duplicateInfo.existing_features[0].id === featureId) {
              // Update the duplicate info with the fresh feature data
              item.duplicateInfo.existing_features[0] = response.data.feature;
            }
          });
        }
      } catch (error) {
        console.error('Error refreshing original feature data:', error);
      }
    },
    clearComponentState() {
      // Stop polling first to prevent API calls with null currentId
      this.stopProcessingPolling();

      // Clear all component data to reset state
      this.msg = "";
      this.currentId = null;
      this.originalFilename = null;
      this.itemsForUser = [];
      this.originalItems = [];
      this.workerLog = [];

      // Reset dialog state
      this.dialogs = {
        mapPreview: false,
        featureMap: {isOpen: false, selectedIndex: 0},
        editOriginal: {isOpen: false, feature: null, loading: false},
        logs: false
      };

      // Reset loading state
      this.loading = {
        logs: true,
        page: false,
        saving: false,
        importing: false,
        redirecting: false
      };

      // Reset processing state
      this.processing = {
        active: false,
        message: '',
        progress: null,
        pollingInterval: null
      };

      // Reset pagination
      this.pagination = {
        currentPage: 1,
        pageSize: 50,
        totalFeatures: 0,
        totalPages: 0,
        hasNext: false,
        hasPrevious: false,
        gotoInput: null
      };

      // Reset duplicates
      this.duplicates = {
        features: [],
        indices: []
      };

      // Reset edit cache
      this.editCache = {
        pages: {},
        originals: {}
      };

      // Reset misc state
      this.lockButtons = false;
      this.isImported = false;
    },
    async loadPage(page) {
      // Cache current page changes before loading a new page
      this.cacheCurrentPageChanges();

      this.loading.page = true;
      try {
        const response = await axios.get(`/api/data/item/import/get/${this.currentId}?page=${page}&page_size=${this.pagination.pageSize}`);
        if (response.data.success) {
          // Update pagination info
          const pagination = response.data.pagination;
          this.pagination.currentPage = pagination.page;
          this.pagination.totalFeatures = pagination.total_features;
          this.pagination.totalPages = pagination.total_pages;
          this.pagination.hasNext = pagination.has_next;
          this.pagination.hasPrevious = pagination.has_previous;
          this.duplicates.indices = pagination.duplicate_indices || [];

          // Parse features for this page
          this.itemsForUser = [];
          response.data.geofeatures.forEach((item) => {
            this.itemsForUser.push(this.parseGeoJson(item));
          });
          this.originalItems = JSON.parse(JSON.stringify(this.itemsForUser));

          // Restore cached changes if they exist for this page
          this.restoreCachedPageChanges(page);

          // Process duplicates from the API response
          this.duplicates.features = response.data.duplicates || [];
          this.markDuplicateFeatures();
        }
      } catch (error) {
        this.msg = 'Error loading page: ' + error.message;
        console.error(error);
      } finally {
        this.loading.page = false;
      }
    },
    async loadLogs() {
      // Load logs from the status endpoint
      this.loading.logs = true;
      try {
        const response = await axios.get(`/api/data/item/import/logs/${this.currentId}`);
        if (response.data.success) {
          this.workerLog = response.data.logs || [];
          // Track the last log ID for incremental updates
          if (this.workerLog.length > 0) {
            this.lastLogId = this.workerLog[this.workerLog.length - 1].id;
          }
        }
      } catch (error) {
        console.error('Error loading logs:', error);
        // Don't set error message as logs are not critical
      } finally {
        this.loading.logs = false;
      }
    },
    async loadLogsIncremental() {
      // Safety check: don't make API calls if currentId is null (component is being destroyed)
      if (!this.currentId) {
        return;
      }

      // Load only new logs since the last fetch
      try {
        const url = this.lastLogId
            ? `/api/data/item/import/logs/${this.currentId}?after_id=${this.lastLogId}`
            : `/api/data/item/import/logs/${this.currentId}`;

        const response = await axios.get(url);
        if (response.data.success && response.data.logs) {
          // Append new logs to existing ones
          this.workerLog = [...this.workerLog, ...response.data.logs];
          // Update the last log ID
          if (response.data.logs.length > 0) {
            this.lastLogId = response.data.logs[response.data.logs.length - 1].id;
          }
        }
      } catch (error) {
        console.error('Error loading incremental logs:', error);
        // Don't set error message as logs are not critical
      }
    },
    cacheCurrentPageChanges() {
      // Store the current page's items in cache
      if (this.pagination.currentPage && this.itemsForUser.length > 0) {
        this.editCache.pages[this.pagination.currentPage] = JSON.parse(JSON.stringify(this.itemsForUser));
        // Also cache the original items for this page for change detection
        if (this.originalItems.length > 0) {
          this.editCache.originals[this.pagination.currentPage] = JSON.parse(JSON.stringify(this.originalItems));
        }
      }
    },
    restoreCachedPageChanges(page) {
      // Restore cached changes for the specified page
      if (this.editCache.pages[page]) {
        this.itemsForUser = JSON.parse(JSON.stringify(this.editCache.pages[page]));
        // Also restore the original items if we have them
        if (this.editCache.originals[page]) {
          this.originalItems = JSON.parse(JSON.stringify(this.editCache.originals[page]));
        }
      }
    },
    async nextPage() {
      if (this.pagination.hasNext) {
        await this.loadPage(this.pagination.currentPage + 1);
      }
    },
    async previousPage() {
      if (this.pagination.hasPrevious) {
        await this.loadPage(this.pagination.currentPage - 1);
      }
    },
    async goToPage(page) {
      if (page >= 1 && page <= this.pagination.totalPages) {
        await this.loadPage(page);
      }
    },
    async jumpToPage() {
      if (this.isValidPageNumber) {
        await this.goToPage(this.pagination.gotoInput);
        this.pagination.gotoInput = null; // Clear the input after jumping
      }
    }
  },
  mounted() {
    // Add navigation warning when user tries to leave the page
    this.beforeUnloadHandler = (event) => {
      // Only warn if we're not redirecting due to import completion
      if (!this.loading.redirecting) {
        event.preventDefault();
        event.returnValue = '';
        return '';
      }
    };
    window.addEventListener('beforeunload', this.beforeUnloadHandler);
  },
  beforeUnmount() {
    // Remove the navigation warning when component is destroyed
    if (this.beforeUnloadHandler) {
      window.removeEventListener('beforeunload', this.beforeUnloadHandler);
    }
    // Clear component state when component is destroyed
    this.clearComponentState();
  },
  beforeRouteLeave(to, from, next) {
    // Skip warning if we're redirecting due to invalid import ID
    if (this.loading.redirecting) {
      // Remove the beforeunload handler before redirecting
      if (this.beforeUnloadHandler) {
        window.removeEventListener('beforeunload', this.beforeUnloadHandler);
      }
      this.clearComponentState();
      next();
      return;
    }

    // Warn user before leaving this route
    const answer = window.confirm('Are you sure you want to leave this page? Your changes may not be saved.');
    if (answer) {
      // Remove the beforeunload handler before navigating away
      if (this.beforeUnloadHandler) {
        window.removeEventListener('beforeunload', this.beforeUnloadHandler);
      }
      // Clear component state when user confirms they want to leave
      this.clearComponentState();
      next();
    } else {
      next(false);
    }
  },
  beforeRouteEnter(to, from, next) {
    const now = new Date().toISOString()
    next(async vm => {
      if (vm.currentId !== vm.id) {
        vm.msg = ""
        vm.currentId = null
        vm.originalFilename = null
        vm.itemsForUser = []
        vm.originalItems = []
        vm.workerLog = []
        vm.lockButtons = false
        vm.isImported = false
        vm.processing.active = false
        vm.processing.message = ''
        vm.processing.progress = null
        vm.duplicates.status = null
        vm.duplicates.originalFilename = null
        vm.duplicates.indices = []
        vm.duplicates.features = []
        vm.pagination.totalFeatures = 0
        vm.pagination.currentPage = 1
        vm.pagination.totalPages = 0
        vm.pagination.hasNext = false
        vm.pagination.hasPrevious = false

        try {
          // Fetch items and logs in parallel for better performance
          const [itemsResponse, logsResponse] = await Promise.all([
            axios.get(`/api/data/item/import/get/${vm.id}?page=1&page_size=${vm.pagination.pageSize}`),
            axios.get(`/api/data/item/import/logs/${vm.id}`)
          ])

          if (!itemsResponse.data.success) {
            vm.msg = capitalizeFirstLetter(itemsResponse.data.msg).trim(".") + "."
          } else {
            vm.currentId = vm.id

            // Load logs first (they're already fetched)
            if (logsResponse.data && logsResponse.data.logs) {
              vm.workerLog = logsResponse.data.logs || []
            }
            vm.processing.active = itemsResponse.data.processing || false

            if (Object.keys(itemsResponse.data).length > 0) {
              vm.originalFilename = itemsResponse.data.original_filename
              vm.isImported = itemsResponse.data.imported || false

              // If the item is already imported, redirect to import page
              if (vm.isImported) {
                // Remove the beforeunload handler before redirecting
                if (vm.beforeUnloadHandler) {
                  window.removeEventListener('beforeunload', vm.beforeUnloadHandler);
                }
                vm.loading.redirecting = true;
                vm.$router.replace('/import');
                return;
              }

              // Update pagination info
              if (itemsResponse.data.pagination) {
                vm.pagination.currentPage = itemsResponse.data.pagination.page;
                vm.pagination.totalFeatures = itemsResponse.data.pagination.total_features;
                vm.pagination.totalPages = itemsResponse.data.pagination.total_pages;
                vm.pagination.hasNext = itemsResponse.data.pagination.has_next;
                vm.pagination.hasPrevious = itemsResponse.data.pagination.has_previous;
                vm.duplicates.indices = itemsResponse.data.pagination.duplicate_indices || [];
              }

              if (itemsResponse.data.geofeatures.length > 0 && itemsResponse.data.geofeatures[0].error) {
                // Check if this is an error response (unprocessable file)
                // This is an unprocessable file, show a simple error message
                const errorItem = itemsResponse.data.geofeatures[0];
                vm.msg = "File processing failed. Please check the processing logs below for details.";
                // Keep the logs we already fetched, but add the error message if not already present
                if (vm.workerLog.length === 0) {
                  vm.workerLog = [{timestamp: now, msg: errorItem.message}];
                }
              } else if (itemsResponse.data.geofeatures.length === 0) {
                // Empty geofeatures - check if still processing
                if (vm.processing.active) {
                  vm.startProcessingPolling()
                }
              } else {
                // Normal processing - parse the geofeatures
                itemsResponse.data.geofeatures.forEach((item) => {
                  vm.itemsForUser.push(vm.parseGeoJson(item))
                })
                vm.originalItems = JSON.parse(JSON.stringify(vm.itemsForUser))

                // Process duplicates from the API response
                vm.duplicates.features = itemsResponse.data.duplicates || []
                vm.markDuplicateFeatures()
              }
            }
          }
        } catch (error) {
          // Check for 404 response from backend (both custom format and HTTP status)
          if ((error.response && error.response.data &&
                  error.response.data.success === false &&
                  error.response.data.code === 404) ||
              (error.response && error.response.status === 404)) {
            // Import ID does not exist - redirect to import page and remove from history
            // Remove the beforeunload handler before redirecting
            if (vm.beforeUnloadHandler) {
              window.removeEventListener('beforeunload', vm.beforeUnloadHandler);
            }
            vm.loading.redirecting = true;
            // Use replace to remove the current entry from browser history
            vm.$router.replace('/import');
            return;
          }
          vm.msg = capitalizeFirstLetter(error.message).trim(".") + "."
        }
      }
    })
  }
}

</script>

<style scoped>

</style>
