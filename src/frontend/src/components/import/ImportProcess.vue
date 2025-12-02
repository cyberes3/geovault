<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div>
          <h1 class="text-xl sm:text-2xl font-bold text-gray-900 mb-1 sm:mb-2">Process Import</h1>
          <h2 v-if="originalFilename != null" class="text-sm sm:text-lg text-gray-600 break-words">{{ originalFilename }}</h2>
          <div v-else class="h-6 w-48 bg-gray-200 rounded animate-pulse"></div>
          <p v-if="uploadTimestamp != null" class="text-xs sm:text-sm text-gray-500 mt-1">{{ formatUploadDate(uploadTimestamp) }}</p>
        </div>
        <div class="flex items-center sm:justify-end">
          <span v-if="isImported" class="inline-flex items-center px-3 py-1 rounded-full text-xs sm:text-sm font-medium bg-green-100 text-green-800">
            <CheckIcon class="w-4 h-4 mr-1" />
            Imported
          </span>
        </div>
      </div>
    </div>

    <!-- Import Logs -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-base sm:text-lg font-semibold text-gray-900">Processing Logs</h2>
        <button
            class="inline-flex items-center p-2 border border-gray-300 shadow-sm text-xs sm:text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            title="Open full log view"
            @click="dialogs.logs = true"
        >
          <ArrowTopRightOnSquareIcon class="w-4 h-4" />
        </button>
      </div>
      <div class="bg-gray-50 rounded-lg p-3 sm:p-4">
        <div ref="logsContainer" class="h-32 overflow-auto">
          <ul class="space-y-1 sm:space-y-0">
            <li
              v-for="(item, index) in filteredWorkerLog"
              :key="`logitem-${index}`"
              class="border-l-4 pl-2 pb-2 sm:py-1"
              :class="item.level >= 40 ? 'bg-red-50 border-red-400' : 'border-transparent'"
            >
              <div class="flex flex-col gap-1 sm:grid sm:grid-cols-[190px_140px_80px_minmax(0,1fr)] sm:gap-x-4 sm:gap-y-1 sm:items-start">
                <!-- Level + Source (first row on mobile, cols 2–3 on desktop) -->
                <div class="flex flex-wrap sm:flex-nowrap items-center gap-2 sm:col-start-2 sm:row-start-1">
                  <span
                    v-if="item.level !== undefined"
                    :class="getLevelClass(item.level)"
                    class="text-[11px] sm:text-xs px-2 py-0.5 rounded font-medium"
                  >
                    {{ getLevelName(item.level) }}
                  </span>
                  <span
                    v-if="item.source"
                    class="text-xs text-gray-400 bg-gray-100 px-2 py-1 rounded whitespace-normal sm:whitespace-nowrap break-words"
                    :title="item.source"
                  >{{ item.source }}</span>
                </div>

                <!-- Timestamp (second row on mobile, col 1 on desktop) -->
                <span
                  class="text-[11px] sm:text-sm text-gray-500 font-mono bg-gray-100 px-2 py-0.5 rounded sm:bg-transparent sm:px-0 sm:py-0 sm:w-fit sm:whitespace-nowrap sm:col-start-1 sm:row-start-1"
                >{{ formatTimestamp(item.timestamp) }}</span>

                <!-- Message (third row on mobile, last column on desktop) -->
                <p
                  :class="item.level >= 40 ? 'text-red-800 font-medium' : 'text-gray-700'"
                  class="text-xs sm:text-sm leading-relaxed break-words sm:col-start-4 sm:row-start-1 sm:row-span-2"
                >
                  {{ item.msg }}
                </p>
              </div>

              <!-- Divider for mobile -->
              <hr v-if="index < filteredWorkerLog.length - 1" class="sm:hidden mt-2 border-gray-200" />
            </li>
            <li v-if="filteredWorkerLog.length === 0" class="text-sm text-gray-500 italic">
              {{ loading.logs ? 'Fetching logs...' : 'No logs available yet...' }}
            </li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Import Summary -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <h3 class="text-base sm:text-lg font-semibold text-gray-900 mb-4">Import Summary</h3>
      <div v-if="loading.page" class="text-center py-8">
        <span class="text-blue-500 font-medium">Loading...</span>
      </div>
      <div v-else class="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div class="bg-blue-50 border border-blue-200 rounded-lg p-4">
          <div class="flex items-center">
            <div class="flex-shrink-0">
              <DocumentIcon class="h-8 w-8 text-blue-600" />
            </div>
            <div class="ml-3">
              <p class="text-sm font-medium text-blue-700">Total Features</p>
              <p class="text-2xl font-bold text-blue-700">{{ pagination.totalFeatures || itemsForUser.length }}</p>
            </div>
          </div>
        </div>

        <div class="bg-green-50 border border-green-200 rounded-lg p-4">
          <div class="flex items-center">
            <div class="flex-shrink-0">
              <CheckIcon class="h-8 w-8 text-green-400" />
            </div>
            <div class="ml-3">
              <p class="text-sm font-medium text-green-800">Ready to Import</p>
              <p class="text-2xl font-bold text-green-900">{{ importableCount }}</p>
            </div>
          </div>
        </div>

        <div class="bg-yellow-50 border border-yellow-200 rounded-lg p-4">
          <div class="flex items-center">
            <div class="flex-shrink-0">
              <ExclamationTriangleIcon class="h-8 w-8 text-yellow-400" />
            </div>
            <div class="ml-3">
              <p class="text-sm font-medium text-yellow-800">Duplicates</p>
              <p class="text-2xl font-bold text-yellow-900">{{ totalDuplicateCount }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Loading State for Initial Page Load and Post-Processing -->
    <Loader
      v-if="(originalFilename == null && !loading.page) || (processing.active && processing.progress === null)"
      :message="loadingMessage"
    />

    <!-- Search Box -->
    <div v-if="itemsForUser.length > 0 && !loading.page && !processing.active" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <div class="flex flex-col gap-4">
        <div class="flex items-center gap-3">
          <div class="flex-1 relative">
            <input
                v-model="searchQuery"
                :disabled="loading.page || isSearching"
                type="text"
                placeholder="Search features..."
                class="block w-full px-4 py-2 pl-10 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                @input="handleSearchInput"
            />
            <MagnifyingGlassIcon class="absolute left-3 top-1/2 transform -translate-y-1/2 h-5 w-5 text-gray-400" />
          </div>
          <button
              v-if="searchQuery"
              @click="clearSearch"
              class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
              title="Clear search"
          >
            <XMarkIcon class="w-4 h-4" />
          </button>
        </div>

        <!-- Search Results -->
        <div v-if="searchQuery && (isSearching || searchResults.length > 0 || (searchQuery && !isSearching && searchResults.length === 0))" class="border-t border-gray-200 pt-4">
          <div v-if="isSearching" class="text-sm text-gray-500 text-center py-4">
            Searching...
          </div>
          <div v-else-if="searchResults.length > 0" class="space-y-2">
            <div class="text-sm font-medium text-gray-700 mb-3">
              Found {{ totalSearchMatches }} result{{ totalSearchMatches !== 1 ? 's' : '' }}
            </div>
            <div class="space-y-2 max-h-96 overflow-y-auto">
              <div
                  v-for="(result, index) in searchResults"
                  :key="index"
                  class="p-3 bg-gray-50 rounded-md border border-gray-200 hover:bg-gray-100 transition-colors"
              >
                <div class="flex items-start justify-between gap-3">
                  <div class="flex-1 min-w-0">
                    <div class="text-sm font-medium text-gray-900 mb-1">
                      {{ result.feature.properties?.name || 'Unnamed Feature' }}
                    </div>
                    <div v-if="result.feature.properties?.description" class="text-xs text-gray-600 mb-2 line-clamp-2">
                      {{ truncateDescription(result.feature.properties.description) }}
                    </div>
                    <div class="text-xs text-gray-500">
                      Page {{ result.page }}, Feature {{ result.feature_index + 1 }}
                    </div>
                  </div>
                  <button
                      @click="goToSearchResult(result)"
                      class="inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 whitespace-nowrap"
                      title="Jump to Feature"
                  >
                    Jump to Feature
                  </button>
                </div>
              </div>
            </div>
          </div>
          <div v-else-if="searchQuery && !isSearching" class="text-sm text-gray-500 text-center py-4">
            No results found
          </div>
        </div>
      </div>
    </div>

    <!-- Controls (Top) -->
    <ImportControls
        :current-page="pagination.currentPage"
        :duplicate-count="totalDuplicateCount"
        :file-duplicate="fileDuplicate"
        :error-message="msg"
        :goto-page-input="pagination.gotoInput"
        :has-features="itemsForUser.length > 0"
        :has-next-page="pagination.hasNext"
        :has-previous-page="pagination.hasPrevious"
        :importable-count="importableCount"
        :is-imported="isImported"
        :is-importing="loading.importing"
        :is-loading-page="loading.page"
        :is-saving="loading.saving"
        :lock-buttons="lockButtons"
        :page-size="pagination.pageSize"
        :show-action-buttons="false"
        :show-duplicate-message="true"
        :show-no-features-message="showNoFeaturesMessage"
        :total-features="pagination.totalFeatures"
        :total-pages="pagination.totalPages"
        @previous-page="previousPage"
        @next-page="nextPage"
        @jump-to-page="goToPage"
        @show-map-preview="showMapPreview"
        @save-changes="saveChanges"
        @perform-import="performImport"
    />

    <!-- Global Options -->
    <div v-if="itemsForUser.length > 0 && !loading.page && !processing.active" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <h3 class="text-sm font-semibold text-gray-900 mb-3">Global Options</h3>
      <div class="flex flex-col sm:flex-row sm:items-center gap-4">
        <!-- Import Custom Icons Toggle -->
        <div class="flex items-center space-x-3">
          <ToggleButton
              v-model="importCustomIcons"
              label="Import custom icons for all features"
              :disabled="lockButtons || loading.importing || loading.saving || isImported"
              size="md"
          />
          <label class="text-sm font-medium text-gray-700 cursor-pointer whitespace-nowrap" @click="!lockButtons && !loading.importing && !loading.saving && !isImported && (importCustomIcons = !importCustomIcons)">
            Import custom icons for all features
          </label>
        </div>

        <!-- Buttons Section -->
        <div class="flex items-center gap-2 sm:ml-auto">
          <!-- Recheck Duplicates Button -->
          <button
              :disabled="lockButtons || loading.importing || loading.saving || isImported || loading.recheckingDuplicates"
              class="inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:bg-gray-400 disabled:cursor-not-allowed transition-colors duration-200 whitespace-nowrap"
              @click="recheckDuplicates"
              title="Recheck for duplicate features"
          >
            <Loader v-if="loading.recheckingDuplicates" size="sm" layout="inline" :showMessage="false" color="white" />
            {{ loading.recheckingDuplicates ? 'Rechecking...' : 'Recheck Duplicates' }}
          </button>

          <!-- Bulk Operations Button -->
          <button
              :disabled="lockButtons || loading.importing || loading.saving || isImported"
              class="inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:bg-gray-400 disabled:cursor-not-allowed transition-colors duration-200 whitespace-nowrap"
              @click="openBulkOperationsModal"
              title="Bulk Operations"
          >
            Bulk Operations
          </button>
          <RectangleStackIcon v-if="hasBulkOperationsConfigured" class="w-5 h-5 text-blue-500 flex-shrink-0" />
        </div>
      </div>
    </div>

    <!-- Controls (Top) -->
    <ImportControls
        v-if="!loading.page"
        :current-page="pagination.currentPage"
        :duplicate-count="totalDuplicateCount"
        :file-duplicate="fileDuplicate"
        :goto-page-input="pagination.gotoInput"
        :has-features="itemsForUser.length > 0"
        :has-next-page="pagination.hasNext"
        :has-previous-page="pagination.hasPrevious"
        :importable-count="importableCount"
        :is-imported="isImported"
        :is-importing="loading.importing"
        :is-loading-page="loading.page"
        :is-saving="loading.saving"
        :lock-buttons="lockButtons"
        :page-size="pagination.pageSize"
        :save-status="saveStatus"
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

    <!-- Processing Status with Progress Bar -->
    <div v-if="processing.active && processing.progress !== null" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-center py-8">
        <div class="text-center">
          <Loader size="md" layout="centered" :message="processing.message" />
          <div class="mt-4">
            <div class="w-full bg-gray-200 rounded-full h-2">
              <div :style="{ width: processing.progress + '%' }" class="bg-blue-500 h-2 rounded-full transition-all duration-300"></div>
            </div>
            <p class="text-sm text-gray-500 mt-2">{{ Math.round(processing.progress) }}% complete</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Feature Items -->
    <div v-else-if="itemsForUser.length > 0 && !loading.page" class="space-y-6">
      <div v-for="(item, index) in itemsForUser" :key="`item-${index}`"
           :data-feature-index="(pagination.currentPage - 1) * pagination.pageSize + index"
           :class="getItemClasses(item, index)">
        <!-- Button row - always fully visible -->
        <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 mb-4 sm:mb-6 relative z-20">
          <h3 class="text-base sm:text-lg font-semibold text-gray-900" :class="isItemSkipped(item, index) && !isItemDuplicate(item) ? 'opacity-50' : ''">
            Feature {{ (pagination.currentPage - 1) * pagination.pageSize + index + 1 }} (of {{ pagination.totalFeatures }})
          </h3>
          <div class="flex flex-wrap items-center gap-2 sm:space-x-2">
            <!-- Icon Preview -->
            <div v-if="getFeatureIconUrl(item)" class="flex items-center justify-center w-8 h-8 p-1 border border-gray-300 rounded bg-white shadow-sm">
              <img
                  :src="getFeatureIconUrl(item)"
                  :alt="'Custom icon for ' + (item.properties.name || 'feature')"
                  class="max-w-full max-h-full object-contain"
                  @error="handleIconError($event)"
              />
            </div>
            <!-- Skip/Restore Button -->
            <button
                v-if="!isImported && !loading.importing"
                :class="isItemDuplicate(item) ? 'relative z-20 inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-400 bg-gray-100 cursor-not-allowed' : (isItemSkipped(item, index) ? 'relative z-20 inline-flex items-center px-3 py-1.5 border border-transparent text-xs font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500' : 'relative z-20 inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-gray-500')"
                @click.stop="isItemDuplicate(item) ? null : toggleSkipItem(index)"
                :disabled="isItemDuplicate(item)"
                :title="isItemDuplicate(item) ? 'Cannot skip duplicate items' : (isItemSkipped(item, index) ? 'Restore this item' : 'Skip this item')"
                type="button"
                style="opacity: 1 !important;"
            >
              <CheckIcon v-if="isItemSkipped(item, index)" class="w-3 h-3 mr-1" />
              <XMarkIcon v-else class="w-3 h-3 mr-1" />
              {{ isItemSkipped(item, index) ? 'Restore' : 'Skip' }}
            </button>
            <button
                :class="isItemSkipped(item, index) ? 'inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-400 bg-gray-100 cursor-not-allowed' : 'inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500'"
                :disabled="isItemSkipped(item, index)"
                @click="showFeatureMap(index)"
                title="View feature on map"
            >
              <MapIcon class="w-3 h-3 mr-1" />
              View on Map
            </button>
            <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-700">
              {{ item.geometry.type }}
            </span>
          </div>
        </div>

        <!-- Duplicate Warnings - outside opacity div so they're always fully visible -->
        <div class="relative z-10">
          <DuplicateWarning type="hash" :item="item" />
          <DuplicateWarning type="coord" :item="item" />
          <DuplicateWarning type="queue" :item="item" />
        </div>

        <!-- Content area - can be greyed out for skipped or duplicate items -->
        <div :class="(isItemSkipped(item, index) && !isItemDuplicate(item)) || isItemDuplicate(item) ? 'opacity-50' : ''">
        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
          <!-- Name Field -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">Name</label>
            <div class="flex items-center space-x-2">
              <input
                  v-model="item.properties.name"
                  :class="isItemDisabled(item, index) ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500'"
                  :disabled="isItemDisabled(item, index)"
                  :placeholder="originalItems[index].properties.name"
              />
              <button
                  :disabled="!isItemEditable(item, index)"
                  class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white"
                  @click="resetNestedField(index, 'properties', 'name')"
                  title="Reset to original name"
              >
                <ArrowPathIcon class="w-4 h-4" />
              </button>
            </div>
          </div>

          <!-- Description Field -->
          <div class="md:col-span-2">
            <label class="block text-sm font-medium text-gray-700 mb-2">Description</label>
            <div class="flex items-start space-x-2">
              <textarea
                  v-model="item.properties.description"
                  :class="isItemDisabled(item, index) ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed resize-none' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 resize-none'"
                  :disabled="isItemDisabled(item, index)"
                  :placeholder="originalItems[index].properties.description"
                  class="text-sm"
                  rows="4"
              ></textarea>
              <button
                  :disabled="!isItemEditable(item, index)"
                  class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white mt-1"
                  @click="resetNestedField(index, 'properties', 'description')"
                  title="Reset to original description"
              >
                <ArrowPathIcon class="w-4 h-4" />
              </button>
            </div>
          </div>

          <!-- Created Date Field -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">Created Date</label>
            <div class="flex items-center space-x-2">
              <input
                  :class="isItemDisabled(item, index) ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500'"
                  :disabled="isItemDisabled(item, index)"
                  :value="formatDateForInput(item.properties.created)"
                  type="datetime-local"
                  @change="updateDate(index, $event)"
              />
              <button
                  :disabled="!isItemEditable(item, index)"
                  class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white"
                  @click="resetNestedField(index, 'properties', 'created')"
                  title="Reset to original date"
              >
                <ArrowPathIcon class="w-4 h-4" />
              </button>
            </div>
          </div>

          <!-- Tags Section -->
          <div>
            <TagPicker
              v-model:tags="item.properties.tags"
              :available-tags="availableUserTags"
              :system-tags="getSystemTags(item)"
              :disabled="isItemDisabled(item, index)"
            />
            <div class="flex items-center space-x-2 mt-3">
              <button
                  :disabled="!isItemEditable(item, index) || isItemSkipped(item, index)"
                  class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white"
                  @click="resetTags(index)"
                  title="Reset all tags to original"
              >
                <ArrowPathIcon class="w-4 h-4 mr-1" />
                Reset Tags
              </button>
            </div>
          </div>
        </div>
        </div>
      </div>
    </div>

    <!-- Controls (Bottom) -->
    <ImportControls
        v-if="!loading.page"
        :current-page="pagination.currentPage"
        :duplicate-count="totalDuplicateCount"
        :file-duplicate="fileDuplicate"
        :goto-page-input="pagination.gotoInput"
        :has-features="itemsForUser.length > 0"
        :has-next-page="pagination.hasNext"
        :has-previous-page="pagination.hasPrevious"
        :importable-count="importableCount"
        :is-imported="isImported"
        :is-importing="loading.importing"
        :is-loading-page="loading.page"
        :is-saving="loading.saving"
        :lock-buttons="lockButtons"
        :page-size="pagination.pageSize"
        :save-status="saveStatus"
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

    <!-- Log View Modal -->
    <LogViewModal
        :is-open="dialogs.logs"
        :logs="filteredWorkerLog"
        @close="closeLogModal"
    />

    <!-- Bulk Operations Modal -->
    <BulkStylingModal
        :is-open="dialogs.bulkOperations"
        :available-tags="availableUserTags"
        :current-bulk-ops="bulkOperations"
        @close="closeBulkOperationsModal"
        @apply="updateBulkOperations"
    />
  </div>
</template>

<script>
import {mapState} from "vuex";
import axios from "axios";
import moment from "moment";
import {capitalizeFirstLetter} from "@/assets/js/string.js";
import {PROCESSING_MESSAGES} from "@/assets/js/constants/processing-messages.js";
import ImportQueue from "@/components/import/parts/ImportQueue.vue";
import {GeoFeatureTypeStrings} from "@/assets/js/types/geofeature-strings";
import {GeoPoint, GeoLineString, GeoPolygon} from "@/assets/js/types/geofeature-types";
import {getCookie} from "@/assets/js/auth.js";
import {APIHOST} from "@/config.js";
// Removed flatpickr dependency - using native HTML5 date input
import Loader from "@/components/parts/Loader.vue";
import { sortUserTagsAlphabetically } from "@/utils/tagUtils.js";
import ToggleButton from "@/components/parts/ToggleButton.vue";
import MapPreviewDialog from "@/components/import/parts/MapPreviewDialog.vue";
import FeatureMapDialog from "@/components/import/parts/FeatureMapDialog.vue";
import LogViewModal from "@/components/import/parts/LogViewModal.vue";
import ImportControls from "@/components/import/parts/ImportControls.vue";
import BulkStylingModal from "@/components/import/parts/BulkStylingModal.vue";
import DuplicateWarning from "@/components/import/parts/DuplicateWarning.vue";
import TagPicker from "@/components/TagPicker.vue";
import { DEFAULT_BULK_OPERATIONS, hasBulkOperationsConfigured, areBulkOperationsEqual, cloneBulkOperations } from "@/utils/bulkOperations.js";
import { CheckIcon, ExclamationCircleIcon, ArrowTopRightOnSquareIcon, DocumentIcon, ExclamationTriangleIcon, ArrowDownTrayIcon, ArrowUpTrayIcon, XMarkIcon, MapIcon, ArrowPathIcon, MagnifyingGlassIcon, RectangleStackIcon } from '@heroicons/vue/24/outline';

export default {
  computed: {
    ...mapState(["userInfo", "userSettings"]),
    hasBulkOperationsConfigured() {
      return hasBulkOperationsConfigured(this.bulkOperations);
    },
    isValidPageNumber() {
      return this.pagination.gotoInput &&
          this.pagination.gotoInput >= 1 &&
          this.pagination.gotoInput <= this.pagination.totalPages &&
          this.pagination.gotoInput !== this.pagination.currentPage;
    },

    showNoFeaturesMessage() {
      return this.originalFilename != null && !this.processing.active && !this.loading.page && this.itemsForUser.length === 0;
    },

    importableCount() {
      // Calculate importable count:
      // Total features - hash duplicates (always blocked) - skipped features
      const hashDups = this.duplicates.features?.hash || [];
      const queueDups = this.duplicates.queue || [];
      
      // Hash duplicates are always blocked regardless of source (FeatureStore or other queue items)
      const blockedDuplicatesCount = hashDups.length + queueDups.length;
      
      const count = this.pagination.totalFeatures - blockedDuplicatesCount - this.skippedFeatureIds.size;
      return Math.max(0, count);
    },

    totalDuplicateCount() {
      // Count all duplicates (hash, queue, and coordinate)
      const hashDups = this.duplicates.features?.hash || [];
      const queueDups = this.duplicates.queue || [];
      const coordDups = this.duplicates.features?.coord || [];
      
      // Use a Set to avoid counting the same feature twice
      // Queue duplicates are objects with .hash property
      const allHashes = new Set([
        ...hashDups,
        ...queueDups.map(d => d.hash),
        ...coordDups
      ]);
      return allHashes.size;
    },

    showDebugLogs() {
      // Get the setting value, default to false if not set
      const settings = this.userSettings || {};
      return settings.import?.show_debug_logs === true;
    },

    filteredWorkerLog() {
      // If showDebugLogs is false, filter out DEBUG level logs (level 10)
      if (!this.showDebugLogs) {
        return this.workerLog.filter(log => log.level !== 10);
      }
      return this.workerLog;
    },

    loadingMessage() {
      if (this.statusMessage) {
        return this.statusDetail
          ? `${this.statusMessage} ${this.statusDetail}`
          : this.statusMessage;
      }
      return 'Loading...';
    }
  },
  components: {
    Loader,
    ToggleButton,
    Importqueue: ImportQueue,
    MapPreviewDialog,
    FeatureMapDialog,
    LogViewModal,
    ImportControls,
    BulkStylingModal,
    DuplicateWarning,
    TagPicker,
    CheckIcon,
    ExclamationCircleIcon,
    ArrowTopRightOnSquareIcon,
    DocumentIcon,
    ExclamationTriangleIcon,
    ArrowDownTrayIcon,
    ArrowUpTrayIcon,
    XMarkIcon,
    MapIcon,
    ArrowPathIcon,
    MagnifyingGlassIcon,
    RectangleStackIcon
  },
  data() {
    return {
      // Core data
      msg: "",
      currentId: null,
      originalFilename: null,
      uploadTimestamp: null,
      itemsForUser: [],
      originalItems: [],
      workerLog: [],

      // Consolidated: Dialog state
      dialogs: {
        mapPreview: false,
        featureMap: {isOpen: false, selectedIndex: 0},
        logs: false,
        bulkOperations: false
      },

      // Consolidated: Loading states
      loading: {
        logs: true,
        page: false,
        saving: false,
        importing: false,
        redirecting: false,
        recheckingDuplicates: false
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
        indices: [],
        queue: []  // Store queue duplicates for counting
      },

      // Consolidated: Edit cache
      editCache: {
        pages: {},
        originals: {},
        skippedFeatureIds: new Set()
      },

      // Skipped items tracking
      skippedFeatureIds: new Set(),

      // Misc state
      lockButtons: false,
      isImported: false,
      fileDuplicate: {
        status: null,
        originalFilename: null
      },
      importCustomIcons: true,
      statusMessage: null,
      statusDetail: null,
      waitingForImportCompletion: false,
      saveStatus: null, // null | 'success' | 'error'
      saveStatusTimeout: null,

      // WebSocket connection
      ws: null,
      wsConnected: false,
      wsReconnectAttempts: 0,
      maxReconnectAttempts: 5,

      // Tag autocomplete state
      availableUserTags: [],

      // Bulk operations state
      bulkOperations: cloneBulkOperations(DEFAULT_BULK_OPERATIONS),
      originalBulkOperations: cloneBulkOperations(DEFAULT_BULK_OPERATIONS),

      // Search state
      searchQuery: '',
      searchResults: [],
      totalSearchMatches: 0,
      isSearching: false,
      searchTimeout: null
    }
  },
  watch: {
    workerLog: {
      handler(newLogs, oldLogs) {
        // Auto-scroll to bottom when new logs are added
        if (newLogs && newLogs.length > 0 && (!oldLogs || newLogs.length > oldLogs.length)) {
          // Use a small delay to ensure DOM is updated
          setTimeout(() => {
            this.scrollLogsToBottom();
          }, 50);
        }
      },
      deep: true,
      immediate: false
    },
    '$route.query.featureHash': {
      handler(newHash) {
        if (newHash) {
          this.$nextTick(() => {
            this.waitForPageLoad().then(() => {
              // Additional wait for items to be populated
              this.waitForItems().then(() => {
                this.scrollToFeatureByHash(newHash);
              });
            });
          });
        }
      },
      immediate: true
    }
  },
  beforeDestroy() {
    // Clean up polling interval
    this.stopProcessingPolling()
  },
  props: ['id'],
  methods: {
    // WebSocket methods
    connectWebSocket() {
      if (!this.currentId) {
        console.warn('Cannot connect WebSocket: currentId is null');
        return;
      }

      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = `${protocol}//${window.location.host}/ws/upload/status/${this.currentId}/`;

      this.ws = new WebSocket(wsUrl);
      this.ws.onopen = this.onWebSocketOpen;
      this.ws.onmessage = this.onWebSocketMessage;
      this.ws.onclose = this.onWebSocketClose;
      this.ws.onerror = this.onWebSocketError;
    },

    onWebSocketOpen() {
      this.wsConnected = true;
      this.wsReconnectAttempts = 0;
    },

    onWebSocketMessage(event) {
      const message = JSON.parse(event.data);

      switch (message.type) {
        case 'initial_state':
          this.handleInitialState(message.data);
          break;
        case 'status':
          this.handleStatusMessage(message.data);
          break;
        case 'status_updated':
          this.handleStatusUpdate(message.data);
          break;
        case 'log_added':
          this.handleLogAdded(message.data);
          break;
        case 'item_completed':
          this.handleItemCompleted(message.data);
          break;
        case 'item_failed':
          this.handleItemFailed(message.data);
          break;
        case 'page':
          this.handlePageData(message.data);
          break;
        case 'logs':
          this.handleLogsData(message.data);
          break;
        case 'item_deleted':
          this.handleItemDeleted(message.data);
          break;
        case 'error':
          this.handleError(message.data);
          break;
      }
    },

    onWebSocketClose(event) {
      this.wsConnected = false;

      // Handle 404 - item not found
      if (event.code === 4004) {
        console.log('Item not found (404) - redirecting to import queue');
        this.loading.redirecting = true;
        this.$router.replace('/import');
        return;
      }

      // Don't attempt reconnect if currentId is null (component being destroyed/navigating away)
      if (!this.currentId) {
        return;
      }

      // Attempt to reconnect if not a normal closure and we haven't exceeded max attempts
      if (event.code !== 1000 && this.wsReconnectAttempts < this.maxReconnectAttempts) {
        this.wsReconnectAttempts++;
        setTimeout(() => this.connectWebSocket(), 2000);
      }
    },

    onWebSocketError(error) {
      console.error('WebSocket error:', error);
    },

    handleInitialState(data) {
      // Set all component data from initial state
      this.originalFilename = data.original_filename;
      this.uploadTimestamp = data.timestamp || null;
      this.isImported = data.imported;
      this.processing.active = data.processing;
      this.fileDuplicate = data.file_duplicate || {
        status: null,
        originalFilename: null
      };

      // Clear status message now that initial state is loaded
      this.statusMessage = null;
      this.statusDetail = null;

      if (data.job_details) {
        this.processing.message = data.job_details.message || 'Processing file...';
        this.processing.progress = data.job_details.progress || 0;
      }

      // Check if file is unparsable (failed processing)
      if (data.unparsable) {
        this.processing.active = false;
        this.processing.message = PROCESSING_MESSAGES.PROCESSING_FAILED;
        this.processing.progress = null;
        // Error message will be set by handlePageData if it contains error data
      }

      if (data.features) {
        this.handlePageData(data.features);
      }

      if (data.logs) {
        // Only replace logs if we don't have any logs yet, or if the new logs are more recent
        const shouldReplace = this.workerLog.length === 0 ||
            (data.logs.length > 0 && this.lastLogId && data.logs[data.logs.length - 1].id > this.lastLogId);

        if (shouldReplace) {
          this.workerLog = data.logs;
          this.lastLogId = data.logs.length > 0 ? data.logs[data.logs.length - 1].id : null;
          // Auto-scroll to bottom when initial logs are loaded
          this.scrollLogsToBottom();
        }
      }

      if (data.duplicates) {
        this.duplicates.features = data.duplicates;
      }

      this.loading.logs = false;
      this.loading.page = false;
    },

    handleStatusUpdate(data) {
      this.processing.message = data.message || 'Processing file...';
      this.processing.progress = data.progress || 0;
    },

    handleStatusMessage(data) {
      // Handle status messages from the backend (e.g., during auto-recheck)
      if (data.message) {
        this.statusMessage = data.message;
        this.statusDetail = data.detail || null;
      }
    },

    handleLogAdded(data) {
      // Check if this log already exists (by ID) to prevent duplicates
      const existingLog = this.workerLog.find(log => log.id === data.id);
      if (existingLog) {
        return;
      }

      this.workerLog.push(data);
      this.lastLogId = data.id;
      // Auto-scroll to bottom when new log is added during processing
      this.scrollLogsToBottom();
    },

    handleItemCompleted(data) {
      this.processing.active = false;
      this.processing.message = 'Loading...';
      this.processing.progress = null;
      this.stopProcessingPolling();

      // Log skipped duplicates to console (silent, no user notification)
      if (data.duplicates_skipped) {
        const hashDups = data.duplicates_skipped.hash || [];
        const coordDups = data.duplicates_skipped.coord || [];

        if (hashDups.length > 0) {
          console.log(`Skipped ${hashDups.length} hash duplicate(s):`);
          hashDups.forEach(feature => {
            if (feature.queue_item_filename) {
              console.log(`  - ${feature.name} (hash: ${feature.hash}) from "${feature.queue_item_filename}"`);
            } else {
              console.log(`  - ${feature.name} (hash: ${feature.hash})`);
            }
          });
        }

        if (coordDups.length > 0) {
          console.log(`Skipped ${coordDups.length} coordinate duplicate(s):`);
          coordDups.forEach(feature => {
            console.log(`  - ${feature.name} (hash: ${feature.hash})`);
          });
        }
      }

      // Check if we're waiting for import completion
      if (this.waitingForImportCompletion) {
        this.waitingForImportCompletion = false;
        this.lockButtons = false;
        this.loading.importing = false;

        // Refresh the import queue
        this.$store.dispatch('refreshImportQueue');

        // Remove the beforeunload handler before redirecting
        if (this.beforeUnloadHandler) {
          window.removeEventListener('beforeunload', this.beforeUnloadHandler);
        }

        // Redirect to import page after successful import
        this.loading.redirecting = true;
        window.alert('Import successful: ' + (data.message || 'Import completed successfully'));
        this.$router.replace('/import');
        return;
      }

      // Keep processing active to show the unified loading spinner
      this.processing.active = true;

      // Refresh the page data
      this.sendWebSocketMessage('refresh', {});
    },

    handleItemFailed(data) {
      this.processing.active = false;
      this.processing.message = 'Processing failed';
      this.processing.progress = null;
      this.stopProcessingPolling();

      // Check if we're waiting for import completion
      if (this.waitingForImportCompletion) {
        this.waitingForImportCompletion = false;
        this.lockButtons = false;
        this.loading.importing = false;

        const errorMessage = data.message || data.error_message || PROCESSING_MESSAGES.PROCESSING_FAILED_DEFAULT;
        this.msg = 'Import failed: ' + errorMessage;
        window.alert(this.msg);
        return;
      }

      this.msg = data.error_message || PROCESSING_MESSAGES.PROCESSING_FAILED_DEFAULT;
    },

    handlePageData(data) {
      this.itemsForUser = [];
      if (data.data && data.data.length > 0) {
        // Check if this is an error response (unprocessable file)
        if (data.data.length === 1 && data.data[0].error) {
          // This is an error object, don't try to parse it as GeoJSON
          const errorItem = data.data[0];
          this.msg = errorItem.message || PROCESSING_MESSAGES.FILE_PROCESSING_FAILED_WITH_LOGS;
          this.processing.active = false;
          this.processing.message = PROCESSING_MESSAGES.PROCESSING_FAILED;
          this.processing.progress = null;
          return;
        }

        data.data.forEach((item) => {
          // Skip error objects
          if (item.error) {
            return;
          }
          this.itemsForUser.push(this.parseGeoJson(item));
        });
        this.originalItems = JSON.parse(JSON.stringify(this.itemsForUser));

        // Restore cached changes if they exist for this page
        this.restoreCachedPageChanges(data.pagination.page);

        // Restore skipped state for items on this page
        this.restoreSkippedStateForPage();
      }

      if (data.pagination) {
        this.pagination.currentPage = data.pagination.page;
        this.pagination.totalFeatures = data.pagination.total_features;
        this.pagination.totalPages = data.pagination.total_pages;
        this.pagination.hasNext = data.pagination.has_next;
        this.pagination.hasPrevious = data.pagination.has_previous;
        this.duplicates.indices = data.pagination.duplicate_indices || [];
      }

      // Restore skipped feature IDs from backend
      if (data.skipped_feature_ids && Array.isArray(data.skipped_feature_ids)) {
        data.skipped_feature_ids.forEach(featureId => {
          this.skippedFeatureIds.add(featureId);
        });
        // Update editCache to persist skipped state
        this.editCache.skippedFeatureIds = new Set(this.skippedFeatureIds);
      }

      // Handle new unified duplicate structure from backend
      // Backend sends: hash_duplicates and coord_duplicates as arrays of objects
      // Convert to old format for compatibility with existing code
      if (data.hash_duplicates && Array.isArray(data.hash_duplicates)) {
        // Separate hash duplicates into queue and store duplicates
        const queueDups = data.hash_duplicates.filter(d => d.queue_item_id);
        const storeDups = data.hash_duplicates.filter(d => !d.queue_item_id);
        
        this.duplicates.features = {
          hash: storeDups.map(d => d.hash),
          coord: []  // Will be populated below
        };
        
        // Store feature_store_id in duplicateInfo for each hash duplicate from FeatureStore
        storeDups.forEach(dupInfo => {
          const pageIndex = dupInfo.page_index;
          if (pageIndex >= 0 && pageIndex < this.itemsForUser.length) {
            if (!this.itemsForUser[pageIndex].duplicateInfo) {
              this.itemsForUser[pageIndex].duplicateInfo = {};
            }
            if (dupInfo.feature_store_id) {
              this.itemsForUser[pageIndex].duplicateInfo.feature_store_id = dupInfo.feature_store_id;
            }
          }
        });
        
        // Queue duplicates (hash duplicates from other queue items)
        this.duplicates.queue = queueDups.map(d => ({
          hash: d.hash,
          page_index: d.page_index,
          queue_item_id: d.queue_item_id,
          queue_item_filename: d.queue_item_filename
        }));
        
        this.markDuplicateFeatures();
        this.markQueueDuplicateFeatures(this.duplicates.queue);
      }
      
      if (data.coord_duplicates && Array.isArray(data.coord_duplicates)) {
        // Coord duplicates with optional queue link info
        const coordHashArray = data.coord_duplicates.map(d => d.hash);
        this.duplicates.features = this.duplicates.features || { hash: [], coord: [] };
        this.duplicates.features.coord = coordHashArray;
        
        // Note: We do NOT auto-skip coordinate duplicates here
        // The backend sends skipped_feature_ids which contains the user's saved skip state
        // (including coordinate duplicates that were auto-skipped on first processing)
        // This ensures user choices (restore/skip) are persisted across page reloads
        
        // Mark coordinate duplicates
        this.markDuplicateFeatures();
        
        // Mark coordinate duplicates with queue or FeatureStore link info
        const coordWithLinkInfo = data.coord_duplicates.filter(d => d.type === 'queue' || d.type === 'feature_store');
        if (coordWithLinkInfo.length > 0) {
          this.markCoordDuplicatesFromQueue(coordWithLinkInfo.map(d => {
            const info = {
              page_index: d.page_index,
              type: d.type
            };
            if (d.type === 'queue') {
              info.queue_item_id = d.queue_item_id;
              info.queue_item_filename = d.queue_item_filename;
              info.hash = d.target_hash;
            } else if (d.type === 'feature_store') {
              info.feature_store_id = d.feature_store_id;
            }
            return info;
          }));
        }
      }

      this.loading.page = false;
    },

    handleLogsData(data) {
      if (data.logs) {
        if (data.after_id) {
          // This is an incremental update - append new logs to existing ones
          this.workerLog = this.workerLog.concat(data.logs);
        } else {
          // This is a full refresh - replace all logs
          this.workerLog = data.logs;
        }
        this.lastLogId = data.logs.length > 0 ? data.logs[data.logs.length - 1].id : null;
        // Auto-scroll to bottom when logs are loaded
        this.scrollLogsToBottom();
      }
      this.loading.logs = false;
    },

    handleItemDeleted(data) {
      // Show notification and redirect
      this.loading.redirecting = true;
      this.$router.push('/import');
    },

    handleError(data) {
      // Handle error messages from WebSocket
      if (data.code === 404) {
        console.log('Item not found (404) - redirecting to import queue');
        this.loading.redirecting = true;
        this.$router.replace('/import');
      } else if (data.code === 409 && data.file_duplicate && data.file_duplicate.status === 'duplicate_in_queue') {
        // Handle duplicate file error with alert popup
        window.alert(data.message || 'This upload is a duplicate and cannot be loaded.');
        // When user closes the alert, redirect to import page
        this.loading.redirecting = true;
        this.$router.replace('/import');
      } else {
        console.error('WebSocket error:', data.message);
        this.msg = data.message || 'An error occurred';
      }
    },

    sendWebSocketMessage(type, data) {
      if (this.ws && this.wsConnected) {
        this.ws.send(JSON.stringify({type, data}));
      }
    },

    async checkProcessingStatus() {
      // Safety check: don't make API calls if currentId is null (component is being destroyed)
      if (!this.currentId) {
        return;
      }

      // Load bulk operations once when loading the page
      this.loadBulkOperations();

      try {
        const response = await axios.get(`/api/item/import/get/${this.currentId}?page=1&page_size=${this.pagination.pageSize}`)
        if (response.status === 200) {
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
          axios.get(`/api/item/import/get/${this.currentId}?page=1&page_size=${this.pagination.pageSize}`),
          axios.get(`/api/item/import/logs/${this.currentId}`)
        ])

        if (itemsResponse.status === 200) {
          // Load logs first (they're already fetched)
          if (logsResponse.data && logsResponse.data.logs) {
            this.workerLog = logsResponse.data.logs || []
            // Auto-scroll to bottom when logs are refreshed
            this.scrollLogsToBottom();
          }
          this.processing.active = itemsResponse.data.processing || false

          if (Object.keys(itemsResponse.data).length > 0) {
            this.originalFilename = itemsResponse.data.original_filename
            this.uploadTimestamp = itemsResponse.data.timestamp || null
            this.isImported = itemsResponse.data.imported || false

            // Load bulk operations once when refreshing import item
            this.loadBulkOperations();

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
                this.msg = latestError.msg || PROCESSING_MESSAGES.FILE_PROCESSING_FAILED_WITH_LOGS;
              } else {
                this.msg = errorItem.message || PROCESSING_MESSAGES.FILE_PROCESSING_FAILED_WITH_LOGS;
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
        return 'bg-blue-100 text-blue-700';
      } else { // DEBUG
        return 'bg-gray-100 text-gray-800';
      }
    },
    formatTimestamp(timestamp) {
      if (!timestamp) return '';
      return moment(timestamp).format('YYYY-MM-DD HH:mm:ss');
    },
    formatUploadDate(timestamp) {
      if (!timestamp) return '';
      // Use moment.js for localized date formatting
      // moment.js will automatically use the browser's locale if available
      return moment(timestamp).format('LLL'); // e.g., "January 15, 2024 2:30 PM" (localized)
    },
    getFeatureIconUrl(feature) {
      /**
       * Get icon URL from feature properties.
       * Checks multiple common property names for icon URLs.
       * @param feature - Feature object with properties
       * @returns Icon URL if found, null otherwise
       */
      if (!feature || !feature.properties) {
        return null;
      }

      // Common property names that might contain icon hrefs
      const iconPropertyNames = [
        'icon',
        'icon-href',
        'iconUrl',
        'icon_url',
        'marker-icon',
        'marker-symbol',
        'symbol',
      ];

      for (const propName of iconPropertyNames) {
        if (feature.properties[propName] && typeof feature.properties[propName] === 'string') {
          const iconUrl = feature.properties[propName].trim();
          if (iconUrl) {
            return this.resolveIconUrl(iconUrl);
          }
        }
      }

      return null;
    },
    resolveIconUrl(iconUrl) {
      /**
       * Resolve icon URL to absolute URL.
       * Converts relative URLs (starting with /api/) to absolute URLs using APIHOST.
       * @param iconUrl - Icon URL (relative or absolute)
       * @returns Absolute icon URL
       */
      // If already absolute URL, return as is
      if (iconUrl.startsWith('http://') || iconUrl.startsWith('https://')) {
        return iconUrl;
      }

      // If relative URL starting with /api/, prepend APIHOST
      // The backend stores icons with path /api/icons/{hash}.png
      if (iconUrl.startsWith('/api/')) {
        return `${APIHOST}${iconUrl}`;
      }

      // Fallback: assume it's a relative path and prepend APIHOST
      return `${APIHOST}${iconUrl.startsWith('/') ? '' : '/'}${iconUrl}`;
    },
    handleIconError(event) {
      /**
       * Handle icon loading errors by hiding the broken image.
       */
      if (event.target && event.target.parentElement) {
        event.target.parentElement.style.display = 'none';
      }
    },
    scrollLogsToBottom() {
      // Scroll the logs container to the bottom when new logs are added
      this.$nextTick(() => {
        if (this.$refs.logsContainer) {
          const container = this.$refs.logsContainer;
          // Use smooth scrolling for better UX
          container.scrollTo({
            top: container.scrollHeight,
            behavior: 'smooth'
          });
        }
      });
    },
    getItemClasses(item, index) {
      // Base classes
      let classes = 'rounded-lg shadow-sm border p-6 relative';

      if (this.isItemDuplicate(item)) {
        classes += ' bg-gray-100 border-gray-300';
      } else if (this.isItemSkipped(item, index)) {
        classes += ' bg-gray-100 border-gray-300';
      } else {
        classes += ' bg-white border-gray-200';
      }

      return classes;
    },
    getFeatureId(item, index) {
      // Get feature ID, using global index as fallback for unique identification
      if (item && item.properties && item.properties.feature_hash) {
        return item.properties.feature_hash;
      }
      // Use global index as fallback - this is unique per feature across all pages
      const globalIndex = (this.pagination.currentPage - 1) * this.pagination.pageSize + index;
      return `index_${globalIndex}`;
    },
    isItemSkipped(item, index) {
      if (!item) {
        return false;
      }
      const featureId = this.getFeatureId(item, index);
      return this.skippedFeatureIds.has(featureId);
    },
    isItemDuplicate(item) {
      return !!(item && (item.isDuplicate || item.isQueueDuplicate));
    },
    isItemDisabled(item, index) {
      return this.isImported ||
             this.isItemDuplicate(item) ||
             this.isItemSkipped(item, index) ||
             this.loading.importing;
    },
    isItemEditable(item, index) {
      return !this.isItemDisabled(item, index);
    },
    async toggleSkipItem(index) {
      const item = this.itemsForUser[index];
      if (!item) {
        console.warn('toggleSkipItem: item not found at index', index);
        return;
      }

      // Get unique feature ID
      const featureId = this.getFeatureId(item, index);

      if (this.skippedFeatureIds.has(featureId)) {
        // Restore item
        this.skippedFeatureIds.delete(featureId);
      } else {
        // Skip item
        this.skippedFeatureIds.add(featureId);
      }

      // Trigger reactivity by creating a new Set (Vue doesn't detect Set modifications)
      this.skippedFeatureIds = new Set(this.skippedFeatureIds);

      // Update editCache to persist skipped state
      this.editCache.skippedFeatureIds = new Set(this.skippedFeatureIds);

      // Skip state will be saved to backend when user clicks "Save Changes"
      // No immediate API call needed here

      // Force Vue to detect the change since Sets are not reactive
      this.$forceUpdate();
    },
    async saveSkipStateToBackend() {
      if (!this.currentId) {
        return;
      }

      const csrftoken = getCookie('csrftoken');
      // Convert skippedFeatureIds Set to array, filtering out index-based IDs (temp IDs)
      const skippedFeatureIdsArray = Array.from(this.skippedFeatureIds).filter(id => !id.startsWith('index_'));

      const response = await axios.put(`/api/item/import/skip-state/${this.currentId}`, {
        skipped_feature_ids: skippedFeatureIdsArray
      }, {
        headers: {
          'X-CSRFToken': csrftoken
        }
      });

      if (response.status !== 200) {
        throw new Error(response.data?.msg || 'Failed to save skip state');
      }
    },
    // Note: MultiPoint and MultiPolygon features may be displayed during import preview,
    // but KML's MultiGeometry converts to GeometryCollection (not MultiPoint/MultiPolygon).
    // If MultiPoint/MultiPolygon appear in processed features, the backend will error/assert.
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
    resetTags(index) {
      // Reset to original user tags
      const originalTags = [...this.originalItems[index].properties.tags];
      this.itemsForUser[index].properties.tags = originalTags;
    },
    getSystemTags(item) {
      if (!item || !item.properties) return [];
      return Array.isArray(item.properties.system_tags)
        ? item.properties.system_tags.filter(tag => tag && tag.trim() !== '')
        : [];
    },
    async fetchUserTags() {
      try {
        const response = await fetch('/api/features/by-tag/');
        const data = await response.json();

        if (response.ok && data.user_tags) {
          // Extract unique tags from the user_tags object keys and sort alphabetically
          this.availableUserTags = sortUserTagsAlphabetically(Object.keys(data.user_tags));
        } else {
          console.error('Failed to fetch user tags:', data.error || 'Unknown error');
          this.availableUserTags = [];
        }
      } catch (error) {
        console.error('Error fetching user tags:', error);
        this.availableUserTags = [];
      }
    },
    updateDate(index, event) {
      const dateValue = event.target.value;
      if (dateValue) {
        // datetime-local format is YYYY-MM-DDTHH:MM (no seconds or timezone)
        // Append ':00Z' to treat it as UTC and convert to ISO format
        this.itemsForUser[index].properties.created = new Date(dateValue + ':00Z').toISOString();
      } else {
        this.itemsForUser[index].properties.created = null;
      }
    },
    formatDateForInput(dateString) {
      if (!dateString) return '';
      // Convert date string to datetime-local format (YYYY-MM-DDTHH:MM)
      const date = new Date(dateString);
      if (isNaN(date.getTime())) return '';
      return date.toISOString().slice(0, 16);
    },
    _prepareFeatureForBackend(feature) {
      // Prepare a partial update for sending to the backend
      // Backend now expects only properties with id, name, description, created, tags
      const properties = feature.properties ? { ...feature.properties } : {};
      // Ensure properties.feature_hash is set - backend requires it to match features
      // Preserve existing properties.feature_hash if present, otherwise use top-level feature.id
      if (!properties.feature_hash) {
        if (feature.id) {
          properties.feature_hash = feature.id;
        }
        // Note: If neither properties.feature_hash nor feature.id exists, the backend will skip this feature
        // This should not happen for valid features from the import queue
      }
      // Extract only the allowed fields: feature_hash, name, description, created, tags
      // feature_hash is required, others are optional
      const partialUpdate = {
        properties: {
          feature_hash: properties.feature_hash
        }
      };
      // Add optional fields only if they are defined
      if (properties.name !== undefined) {
        partialUpdate.properties.name = properties.name;
      }
      if (properties.description !== undefined) {
        partialUpdate.properties.description = properties.description;
      }
      if (properties.created !== undefined && properties.created !== null) {
        partialUpdate.properties.created = properties.created;
      }
      if (properties.tags !== undefined) {
        partialUpdate.properties.tags = properties.tags;
      }
      return partialUpdate;
    },
    _getChangedFeatures() {
      // Helper method to collect changed features from current page and cached pages
      // Returns an array of changed features (in comparable format)
      const changedFeatures = [];

      // Helper function to get comparable feature data (excluding UI-only properties)
      // This is used for comparison only - does not modify properties
      const getComparableFeature = (feature) => {
        // Tags are already separated - user tags only in tags field
        const properties = { ...feature.properties };
        return {
          type: feature.type,
          geometry: feature.geometry,
          properties: properties
        };
      };

      // Helper function to check if a feature has changed
      const hasChanged = (current, original) => {
        if (!current || !original) return false;
        const currentComparable = getComparableFeature(current);
        const originalComparable = getComparableFeature(original);
        return JSON.stringify(currentComparable) !== JSON.stringify(originalComparable);
      };

      // Check current page for changes
      this.itemsForUser.forEach((feature, idx) => {
        if (!feature.isDuplicate && hasChanged(feature, this.originalItems[idx])) {
          // Use _prepareFeatureForBackend to ensure properties.feature_hash is set for backend
          changedFeatures.push(this._prepareFeatureForBackend(feature));
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
                // Use _prepareFeatureForBackend to ensure properties.feature_hash is set for backend
                changedFeatures.push(this._prepareFeatureForBackend(feature));
              }
            }
          });
        }
      });

      return changedFeatures;
    },
    hasUnsavedChanges() {
      // Check if there are any unsaved changes on current page or cached pages
      const hasFeatureChanges = this._getChangedFeatures().length > 0;

      // Check if bulk operations have changed
      const hasBulkOpsChanges = this._hasBulkOperationsChanged();

      return hasFeatureChanges || hasBulkOpsChanges;
    },
    _hasBulkOperationsChanged() {
      // Compare current bulk operations with original using shared helper
      return !areBulkOperationsEqual(this.bulkOperations, this.originalBulkOperations);
    },
    async _saveChangesInternal() {
      // Internal save function that doesn't manage locks
      // This can be called by both saveChanges() and performImport()

      // Cache current page changes first
      this.cacheCurrentPageChanges();

      // Collect only changed features from current page and cached pages
      const changedFeatures = this._getChangedFeatures();

      // Check if bulk operations have changed
      const hasBulkOpsChanges = this._hasBulkOperationsChanged();

      // Save bulk operations if they've changed
      if (hasBulkOpsChanges) {
        await this.saveBulkOperations(this.bulkOperations);
      }

      // Always save skip state, even if no other changes
      await this.saveSkipStateToBackend();

      if (changedFeatures.length === 0 && !hasBulkOpsChanges) {
        // No feature changes to save, but skip state was saved above
        return {success: true, changedCount: 0};
      }


      const csrftoken = getCookie('csrftoken');

      // Save only changed features using the new API format
      const response = await axios.put('/api/item/import/update/' + this.currentId, {
        features: changedFeatures
      }, {
        headers: {
          'X-CSRFToken': csrftoken
        }
      });

      if (response.status === 200) {
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

      // Clear any existing save status timeout
      if (this.saveStatusTimeout) {
        clearTimeout(this.saveStatusTimeout);
        this.saveStatusTimeout = null;
      }
      this.saveStatus = null;

      try {
        await this._saveChangesInternal();

        // Unlock buttons and stop showing saving state
        this.lockButtons = false;
        this.loading.saving = false;

        // Show success state for 2 seconds
        this.saveStatus = 'success';
        this.saveStatusTimeout = setTimeout(() => {
          this.saveStatus = null;
          this.saveStatusTimeout = null;
        }, 2000);

      } catch (error) {
        const errorMsg = 'Error saving changes: ' + (error.response?.data?.error || error.response?.data?.msg || error.message);

        // Keep loading state to false but keep buttons locked
        this.loading.saving = false;
        // Keep lockButtons = true to disable save and import buttons permanently

        // Show error state permanently (no timeout)
        this.saveStatus = 'error';

        // Show popup with error message
        window.alert(errorMsg + '\n\nPlease reload the page to try again.');

        console.error("Failed to save: " + error)
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
          }
        } catch (saveError) {
          this.msg = 'Error saving changes before import: ' + (saveError.response?.data?.msg || saveError.message);
          window.alert(this.msg);
          // Reset state before returning
          this.lockButtons = false;
          this.loading.importing = false;
          this.waitingForImportCompletion = false;
          return; // Don't proceed with import if save fails
        }

        // Perform the import - server returns immediately, completion will come via WebSocket
        // No need to send the feature collection, it's already saved
        // Only send features that the USER explicitly chose to skip
        const manuallySkipped = Array.from(this.skippedFeatureIds).filter(id => !id.startsWith('index_'));
        const queueDuplicateHashes = (this.duplicates.queue || []).map(d => d.hash);
        const skippedFeatureIdsArray = [...manuallySkipped, ...queueDuplicateHashes];

        // Set flag BEFORE making the request so WebSocket messages are handled correctly
        // (WebSocket events can arrive faster than the axios response)
        this.waitingForImportCompletion = true;

        const response = await axios.post('/api/item/import/perform/' + this.currentId, {
          import_custom_icons: this.importCustomIcons,
          skipped_feature_ids: skippedFeatureIdsArray
        }, {
          headers: {
            'X-CSRFToken': csrftoken
          }
        });

        if (response.status !== 200) {
          this.msg = 'Error performing import: ' + response.data.msg;
          window.alert(this.msg);
          this.lockButtons = false;
          this.loading.importing = false;
          this.waitingForImportCompletion = false;
        }
        // If status is 200, keep buttons locked and wait for WebSocket event
      } catch (error) {
        this.msg = 'Error performing import: ' + (error.response?.data?.msg || error.message);
        window.alert(this.msg);
        this.lockButtons = false;
        this.loading.importing = false;
        this.waitingForImportCompletion = false;
      }
    },
    async recheckDuplicates() {
      this.lockButtons = true;
      this.loading.recheckingDuplicates = true;
      const csrftoken = getCookie('csrftoken');

      try {
        // Call the recheck duplicates endpoint
        const response = await axios.post('/api/item/import/recheck-duplicates/' + this.currentId, {}, {
          headers: {
            'X-CSRFToken': csrftoken
          }
        });

        if (response.status === 200) {
          // Refresh the page data via WebSocket to get updated duplicates and logs
          this.sendWebSocketMessage('refresh', {});
        } else {
          this.msg = 'Error rechecking duplicates: ' + response.data.msg;
          window.alert(this.msg);
        }
      } catch (error) {
        this.msg = 'Error rechecking duplicates: ' + (error.response?.data?.msg || error.message);
        window.alert(this.msg);
      } finally {
        this.lockButtons = false;
        this.loading.recheckingDuplicates = false;
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
      // Reset duplicate flags but preserve duplicateInfo (like feature_store_id)
      this.itemsForUser.forEach((item, index) => {
        item.isDuplicate = false;
        item.isCoordDuplicate = false;
        // Don't reset duplicateInfo - it may contain feature_store_id or other link info
        // Just clear the type if it exists
        if (item.duplicateInfo) {
          delete item.duplicateInfo.type;
        }
      });

      // Handle new structure: {hash: [], coord: []}
      const hashDuplicates = this.duplicates.features.hash || [];
      const coordDuplicates = this.duplicates.features.coord || [];

      // Mark hash duplicates (blocked, cannot be unskipped)
      // The backend sends hashes that match the feature's properties.feature_hash
      hashDuplicates.forEach(featureHash => {
        this.itemsForUser.forEach((item, index) => {
          const featureId = this.getFeatureId(item, index);
          // Compare the hash from backend with the feature's ID
          if (featureId === featureHash) {
            item.isDuplicate = true;
            // Preserve existing duplicateInfo (like feature_store_id) and add type
            if (!item.duplicateInfo) {
              item.duplicateInfo = {};
            }
            item.duplicateInfo.type = 'hash';
          }
        });
      });

      // Mark coordinate duplicates (default skipped, but can be unskipped)
      coordDuplicates.forEach(featureHash => {
        this.itemsForUser.forEach((item, index) => {
          const featureId = this.getFeatureId(item, index);
          // Compare the hash from backend with the feature's ID
          if (featureId === featureHash) {
            item.isCoordDuplicate = true;
            // Preserve existing duplicateInfo (like coordFeatureStoreInfo) and add type
            if (!item.duplicateInfo) {
              item.duplicateInfo = {};
            }
            item.duplicateInfo.type = 'coord';
          }
        });
      });
    },
    markQueueDuplicateFeatures(queueDuplicates) {
      // Reset all features to not be queue duplicates
      this.itemsForUser.forEach((item, index) => {
        item.isQueueDuplicate = false;
        item.queueDuplicateInfo = null;
      });

      // Mark queue duplicate features using the page_index
      // Note: We do NOT auto-skip queue duplicates here
      // The backend sends skipped_feature_ids which contains the user's saved skip state
      // This ensures user choices (restore/skip) are persisted across page reloads
      queueDuplicates.forEach(queueDuplicateInfo => {
        const pageIndex = queueDuplicateInfo.page_index;
        if (pageIndex >= 0 && pageIndex < this.itemsForUser.length) {
          this.itemsForUser[pageIndex].isQueueDuplicate = true;
          this.itemsForUser[pageIndex].queueDuplicateInfo = queueDuplicateInfo;
        }
      });
    },
    
    markCoordDuplicatesFromQueue(coordDuplicatesInfo) {
      // Mark coordinate duplicate features that are from another queue item or FeatureStore
      // These should show a button to view in the other queue item or on the map
      coordDuplicatesInfo.forEach(coordDupInfo => {
        const pageIndex = coordDupInfo.page_index;
        if (pageIndex >= 0 && pageIndex < this.itemsForUser.length) {
          // Add link info to the feature so the DuplicateWarning component can show a link
          if (!this.itemsForUser[pageIndex].duplicateInfo) {
            this.itemsForUser[pageIndex].duplicateInfo = {};
          }
          
          // Check if it's a queue duplicate or FeatureStore duplicate
          if (coordDupInfo.type === 'queue') {
            this.itemsForUser[pageIndex].duplicateInfo.coordQueueInfo = {
              queue_item_id: coordDupInfo.queue_item_id,
              queue_item_filename: coordDupInfo.queue_item_filename,
              hash: coordDupInfo.hash
            };
          } else if (coordDupInfo.type === 'feature_store') {
            this.itemsForUser[pageIndex].duplicateInfo.coordFeatureStoreInfo = {
              feature_store_id: coordDupInfo.feature_store_id
            };
          }
        }
      });
    },
    closeLogModal() {
      this.dialogs.logs = false;
    },
    openBulkOperationsModal() {
      // Bulk operations are already loaded on component mount
      this.dialogs.bulkOperations = true;
    },
    closeBulkOperationsModal() {
      this.dialogs.bulkOperations = false;
    },
    async loadBulkOperations() {
      if (!this.currentId) return;

      try {
        const csrftoken = getCookie('csrftoken');
        const response = await axios.get(`/api/item/import/bulk-operations/${this.currentId}/get`, {
          headers: {
            'X-CSRFToken': csrftoken
          }
        });

        if (response.status === 200 && response.data.bulk_operations) {
          const ops = response.data.bulk_operations;
          this.bulkOperations = cloneBulkOperations(ops);
        } else {
          // No bulk operations found, use defaults
          this.bulkOperations = cloneBulkOperations(DEFAULT_BULK_OPERATIONS);
        }

        // Store as original state
        this.originalBulkOperations = cloneBulkOperations(this.bulkOperations);
      } catch (error) {
        // Log error and use defaults
        console.error('Error loading bulk operations:', error);
        this.bulkOperations = cloneBulkOperations(DEFAULT_BULK_OPERATIONS);
        // Store as original state
        this.originalBulkOperations = cloneBulkOperations(this.bulkOperations);
      }
    },
    updateBulkOperations(bulkData) {
      // Update local state only (don't save to database yet)
      // Saving will happen when user clicks "Save Changes"
      this.bulkOperations = cloneBulkOperations(bulkData);
    },
    async saveBulkOperations(bulkData) {
      if (!this.currentId) return;

      try {
        const csrftoken = getCookie('csrftoken');
        const response = await axios.put(`/api/item/import/bulk-operations/${this.currentId}`, {
          bulk_operations: bulkData
        }, {
          headers: {
            'X-CSRFToken': csrftoken
          }
        });

        if (response.status === 200) {
          // Update local state with the data we sent (request data)
          this.bulkOperations = cloneBulkOperations(bulkData);
          // Update original state to reflect saved state
          this.originalBulkOperations = cloneBulkOperations(bulkData);
        }
      } catch (error) {
        this.msg = 'Error saving bulk operations: ' + (error.response?.data?.error || error.response?.data?.msg || error.message);
        window.alert(this.msg);
        throw error; // Re-throw so _saveChangesInternal can handle it
      }
    },
    // Removed applyBulkStyling - bulk operations are now stored and applied during import
    // Old method kept for reference but not used
      _old_applyBulkStyling(bulkData) {
       // Apply bulk operations to all items in the current page
      // Also need to apply to all cached pages

      // Apply to current page items
      this.itemsForUser.forEach((item, index) => {
        // Skip duplicates and skipped items
        if (this.isItemDuplicate(item) || this.isItemSkipped(item, index)) {
          return;
        }

        // Apply tags (merge with existing tags, avoiding duplicates)
        if (bulkData.tags && bulkData.tags.length > 0) {
          if (!item.properties.tags) {
            item.properties.tags = [];
          }
          // Merge tags, avoiding duplicates
          const existingTags = new Set(item.properties.tags.map(t => t.toLowerCase()));
          bulkData.tags.forEach(tag => {
            const lowerTag = tag.toLowerCase();
            if (!existingTags.has(lowerTag)) {
              item.properties.tags.push(lowerTag);
            }
          });
        }

        const geometryType = item.geometry?.type;

        // Apply point styling
        if (geometryType === 'Point') {
          if (bulkData.pointColor) {
            item.properties['marker-color'] = bulkData.pointColor;
          }
          if (bulkData.pointIcon !== null) {
            // Set icon_url property (the backend uses icon_url)
            item.properties.icon_url = bulkData.pointIcon;
            // Also set other icon properties for compatibility
            item.properties.iconUrl = bulkData.pointIcon;
            item.properties['icon-href'] = bulkData.pointIcon;
          }
        }

        // Apply line styling
        if (geometryType === 'LineString') {
          if (bulkData.lineColor) {
            item.properties.stroke = bulkData.lineColor;
          }
        }

        // Apply polygon styling
        if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
          if (bulkData.polyColor) {
            item.properties.fill = bulkData.polyColor;
          }
        }
      });

      // Apply to all cached pages
      Object.keys(this.editCache.pages).forEach(pageKey => {
        const pageNum = parseInt(pageKey);
        const cachedItems = this.editCache.pages[pageNum];

        if (cachedItems && Array.isArray(cachedItems)) {
          cachedItems.forEach((item, index) => {
            // Skip duplicates and skipped items
            // Calculate feature ID manually for cached pages (getFeatureId uses currentPage which won't be correct)
            let featureId;
            if (item && item.properties && item.properties.feature_hash) {
              featureId = item.properties.feature_hash;
            } else {
              const globalIndex = (pageNum - 1) * this.pagination.pageSize + index;
              featureId = `index_${globalIndex}`;
            }
            if (this.isItemDuplicate(item) || this.skippedFeatureIds.has(featureId)) {
              return;
            }

            // Apply tags (merge with existing tags, avoiding duplicates)
            if (bulkData.tags && bulkData.tags.length > 0) {
              if (!item.properties.tags) {
                item.properties.tags = [];
              }
              // Merge tags, avoiding duplicates
              const existingTags = new Set(item.properties.tags.map(t => t.toLowerCase()));
              bulkData.tags.forEach(tag => {
                const lowerTag = tag.toLowerCase();
                if (!existingTags.has(lowerTag)) {
                  item.properties.tags.push(lowerTag);
                }
              });
            }

            const geometryType = item.geometry?.type;

            // Apply point styling
            if (geometryType === 'Point') {
              if (bulkData.pointColor) {
                item.properties['marker-color'] = bulkData.pointColor;
              }
              if (bulkData.pointIcon !== null) {
                item.properties.icon_url = bulkData.pointIcon;
                item.properties.iconUrl = bulkData.pointIcon;
                item.properties['icon-href'] = bulkData.pointIcon;
              }
            }

            // Apply line styling
            if (geometryType === 'LineString') {
              if (bulkData.lineColor) {
                item.properties.stroke = bulkData.lineColor;
              }
            }

            // Apply polygon styling
            if (geometryType === 'Polygon' || geometryType === 'MultiPolygon') {
              if (bulkData.polyColor) {
                item.properties.fill = bulkData.polyColor;
              }
            }
          });
        }
      });

      // Cache current page changes
      this.cacheCurrentPageChanges();

      // Force Vue to detect the changes
      this.$forceUpdate();
    },
    clearComponentState() {
      // Stop polling first to prevent API calls with null currentId
      this.stopProcessingPolling();

      // Close WebSocket connection before clearing state
      if (this.ws) {
        this.ws.close(1000); // Normal closure code
        this.ws = null;
      }

      // Clear all component data to reset state
      this.msg = "";
      this.currentId = null;
      this.originalFilename = null;
      this.uploadTimestamp = null;
      this.itemsForUser = [];
      this.originalItems = [];
      this.workerLog = [];

      // Reset dialog state
      this.dialogs = {
        mapPreview: false,
        featureMap: {isOpen: false, selectedIndex: 0},
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

      // Reset file duplicate status
      this.fileDuplicate = {
        status: null,
        originalFilename: null
      };

      // Reset edit cache
      this.editCache = {
        pages: {},
        originals: {},
        skippedFeatureIds: new Set()
      };

      // Reset skipped feature IDs
      this.skippedFeatureIds = new Set();

      // Reset misc state
      this.lockButtons = false;
      this.isImported = false;
      this.importCustomIcons = true;

      // Reset search state
      this.searchQuery = '';
      this.searchResults = [];
      this.totalSearchMatches = 0;
      this.isSearching = false;
      if (this.searchTimeout) {
        clearTimeout(this.searchTimeout);
        this.searchTimeout = null;
      }
    },
    async loadPage(page) {
      // Cache current page changes before loading a new page
      this.cacheCurrentPageChanges();

      this.loading.page = true;
      // Request page data via WebSocket
      this.sendWebSocketMessage('request_page', {page, page_size: this.pagination.pageSize});
    },
    async loadLogs() {
      // Load logs via WebSocket
      this.loading.logs = true;
      this.sendWebSocketMessage('request_logs', {});
    },
    async loadLogsIncremental() {
      // Safety check: don't make API calls if currentId is null (component is being destroyed)
      if (!this.currentId) {
        return;
      }

      // Request incremental logs via WebSocket
      this.sendWebSocketMessage('request_logs', {after_id: this.lastLogId});

      // Also trigger scroll after a short delay to catch any logs that might be added
      setTimeout(() => {
        this.scrollLogsToBottom();
      }, 100);
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
      // Persist skipped feature IDs to editCache
      this.editCache.skippedFeatureIds = new Set(this.skippedFeatureIds);
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
      // Restore skipped feature IDs from editCache
      if (this.editCache.skippedFeatureIds) {
        this.skippedFeatureIds = new Set(this.editCache.skippedFeatureIds);
      }
    },
    restoreSkippedStateForPage() {
      // Skipped state is now handled by isItemSkipped() which checks skippedFeatureIds
      // No additional action needed here
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
    },
    handleSearchInput() {
      // Clear existing timeout
      if (this.searchTimeout) {
        clearTimeout(this.searchTimeout);
      }

      // Debounce search - wait 300ms after user stops typing
      this.searchTimeout = setTimeout(() => {
        this.performSearch();
      }, 300);
    },
    async performSearch() {
      if (!this.searchQuery || !this.searchQuery.trim()) {
        this.searchResults = [];
        this.totalSearchMatches = 0;
        this.isSearching = false;
        return;
      }

      if (!this.currentId) {
        return;
      }

      this.isSearching = true;

      try {
        const query = encodeURIComponent(this.searchQuery.trim());
        const response = await axios.get(`/api/item/import/search/${this.currentId}?query=${query}`);

        if (response.status === 200 && response.data) {
          this.searchResults = response.data.matches || [];
          this.totalSearchMatches = response.data.total_matches || 0;
        } else {
          console.error('Search failed:', response.data?.error || 'Unknown error');
          this.searchResults = [];
          this.totalSearchMatches = 0;
        }
      } catch (error) {
        console.error('Error searching features:', error);
        this.searchResults = [];
        this.totalSearchMatches = 0;
      } finally {
        this.isSearching = false;
      }
    },
    clearSearch() {
      this.searchQuery = '';
      this.searchResults = [];
      this.totalSearchMatches = 0;
      this.isSearching = false;
      if (this.searchTimeout) {
        clearTimeout(this.searchTimeout);
        this.searchTimeout = null;
      }
    },
    async goToSearchResult(result) {
      // Navigate to the page containing the search result
      if (result.page && result.page >= 1 && result.page <= this.pagination.totalPages) {
        await this.goToPage(result.page);

        // Wait for the page to finish loading
        await this.waitForPageLoad();

        // Scroll to the feature using its global index
        this.$nextTick(() => {
          setTimeout(() => {
            this.scrollToFeature(result.feature_index);
          }, 100);
        });

        // Clear search after navigating
        this.clearSearch();
      }
    },
    waitForPageLoad() {
      return new Promise((resolve) => {
        if (!this.loading.page) {
          resolve();
          return;
        }

        const checkInterval = setInterval(() => {
          if (!this.loading.page) {
            clearInterval(checkInterval);
            resolve();
          }
        }, 50);

        // Timeout after 5 seconds
        setTimeout(() => {
          clearInterval(checkInterval);
          resolve();
        }, 5000);
      });
    },
    waitForItems() {
      return new Promise((resolve) => {
        if (this.itemsForUser && this.itemsForUser.length > 0) {
          // Wait a bit more for DOM to update
          this.$nextTick(() => {
            setTimeout(() => resolve(), 200);
          });
          return;
        }

        const checkInterval = setInterval(() => {
          if (this.itemsForUser && this.itemsForUser.length > 0) {
            clearInterval(checkInterval);
            this.$nextTick(() => {
              setTimeout(() => resolve(), 200);
            });
          }
        }, 50);

        // Timeout after 5 seconds
        setTimeout(() => {
          clearInterval(checkInterval);
          resolve();
        }, 5000);
      });
    },
    scrollToFeature(globalIndex) {
      // Find the feature element by its data-feature-index attribute
      const featureElement = document.querySelector(`[data-feature-index="${globalIndex}"]`);

      if (featureElement) {
        // Scroll to the feature with smooth behavior
        featureElement.scrollIntoView({
          behavior: 'smooth',
          block: 'center'
        });

        // Briefly highlight the feature
        featureElement.classList.add('ring-2', 'ring-blue-500', 'ring-offset-2');
        setTimeout(() => {
          featureElement.classList.remove('ring-2', 'ring-blue-500', 'ring-offset-2');
        }, 2000);
      }
    },
    scrollToFeatureByHash(hash) {
      // Find feature with matching hash in current page
      const featureIndex = this.itemsForUser.findIndex(item =>
        item.properties && item.properties.feature_hash === hash
      );

      if (featureIndex >= 0) {
        const globalIndex = (this.pagination.currentPage - 1) * this.pagination.pageSize + featureIndex;
        this.scrollToFeature(globalIndex);
      } else {
        console.warn(`Feature with hash ${hash} not found on current page`);
      }
    },
    truncateDescription(description) {
      if (!description) return '';
      const maxLength = 100;
      if (description.length <= maxLength) return description;
      return description.substring(0, maxLength) + '...';
    }
  },
  async mounted() {
    // Add navigation warning when user tries to leave the page
    this.beforeUnloadHandler = (event) => {
      // Only warn if we're not redirecting due to import completion and there are unsaved changes
      if (!this.loading.redirecting && this.hasUnsavedChanges()) {
        event.preventDefault();
        event.returnValue = '';
        return '';
      }
    };
    window.addEventListener('beforeunload', this.beforeUnloadHandler);

    // Fetch available user tags for autocomplete
    await this.fetchUserTags();

    // Check for featureHash query parameter to scroll to a specific feature
    // This is handled by the route watcher, but we also check here for initial mount
    if (this.$route.query.featureHash) {
      this.$nextTick(() => {
        this.waitForPageLoad().then(() => {
          this.waitForItems().then(() => {
            this.scrollToFeatureByHash(this.$route.query.featureHash);
          });
        });
      });
    }
  },
  beforeUnmount() {
    // Remove the navigation warning when component is destroyed
    if (this.beforeUnloadHandler) {
      window.removeEventListener('beforeunload', this.beforeUnloadHandler);
    }
    // Close WebSocket connection
    if (this.ws) {
      this.ws.close();
      this.ws = null;
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

    // Only warn user if there are unsaved changes
    if (this.hasUnsavedChanges()) {
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
    } else {
      // No unsaved changes, proceed with navigation
      if (this.beforeUnloadHandler) {
        window.removeEventListener('beforeunload', this.beforeUnloadHandler);
      }
      this.clearComponentState();
      next();
    }
  },
  beforeRouteUpdate(to, from, next) {
    // Close existing WebSocket before switching to new item
    if (this.ws) {
      this.ws.close(1000); // Normal closure
      this.ws = null;
    }

    // Update to new ID and reconnect
    this.clearComponentState();
    this.currentId = to.params.id;
    this.connectWebSocket();
    // Load bulk operations once when route updates
    this.loadBulkOperations();

    next();
  },
  beforeRouteEnter(to, from, next) {
    const now = new Date().toISOString()
    next(async vm => {
      if (vm.currentId !== vm.id) {
        vm.msg = ""
        vm.currentId = null
        vm.originalFilename = null
        vm.uploadTimestamp = null
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

        // Set currentId and connect WebSocket
        vm.currentId = vm.id
        vm.connectWebSocket()
        // Load bulk operations once when route is entered
        vm.loadBulkOperations()
      }
    })
  }
}

</script>

<style scoped>
.tag-input {
  text-transform: lowercase;
}
</style>
