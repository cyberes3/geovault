<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <ImportProcessHeader 
      :original-filename="originalFilename"
      :upload-timestamp="uploadTimestamp"
      :is-imported="isImported"
    />

    <!-- Import Logs -->
    <ProcessingLogsPanel 
      :logs="filteredWorkerLog"
      :is-loading="loading.logs"
      @open-full-logs="dialogs.logs = true"
    />

    <!-- Import Summary -->
    <ImportSummaryStats 
      :total-features="pagination.totalFeatures || itemsForUser.length"
      :importable-count="importableCount"
      :duplicate-count="totalDuplicateCount"
      :is-loading="loading.page"
    />

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
                class="block w-full px-4 py-2 pl-10 border-2 border-blue-500 rounded-md focus:ring-blue-500 focus:border-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
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
                      {{ result.feature.properties?.name || '' }}
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

    <!-- Global Options -->
    <GlobalOptionsPanel
      :has-features="itemsForUser.length > 0"
      :is-loading="loading.page"
      :is-processing="processing.active"
      v-model:import-custom-icons="importCustomIcons"
      :lock-buttons="lockButtons"
      :is-importing="loading.importing"
      :is-saving="loading.saving"
      :is-imported="isImported"
      :is-rechecking-duplicates="loading.recheckingDuplicates"
      :has-bulk-operations-configured="hasBulkOperationsConfigured"
      @recheck-duplicates="recheckDuplicates"
      @open-bulk-operations="openBulkOperationsModal"
    />

    <!-- Controls (Top) -->
    <ImportControls
        :current-page="pagination.currentPage"
        :duplicate-count="totalDuplicateCount"
        :file-duplicate="fileDuplicate"
        :error-message="msg"
        :goto-page-input="pagination.gotoInput"
        :has-features="itemsForUser.length > 0"
        :has-next-page="adjustedHasNext"
        :has-previous-page="adjustedHasPrevious"
        :hide-duplicates="hideDuplicates"
        :importable-count="importableCount"
        :is-imported="isImported"
        :is-importing="loading.importing"
        :is-loading-page="loading.page"
        :is-saving="loading.saving"
        :lock-buttons="lockButtons"
        :page-size="pagination.pageSize"
        :save-status="saveStatus"
        :show-action-buttons="true"
        :show-duplicate-message="true"
        :show-no-features-message="showNoFeaturesMessage"
        :total-features="pagination.totalFeatures"
        :total-pages="adjustedTotalPages"
        @previous-page="previousPage"
        @next-page="nextPage"
        @jump-to-page="goToPage"
        @show-map-preview="showMapPreview"
        @save-changes="saveChanges"
        @perform-import="performImport"
        @toggle-hide-duplicates="hideDuplicates = $event"
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

    <!-- Empty state when all items on page are duplicates and hidden -->
    <div v-else-if="showEmptyPageMessage" class="bg-white rounded-lg shadow-sm border border-gray-200 p-12 text-center">
      <div class="flex flex-col items-center">
        <ExclamationTriangleIcon class="h-12 w-12 text-yellow-400 mb-4" />
        <h3 class="text-lg font-medium text-gray-900 mb-2">All items on this page are duplicates</h3>
        <p class="text-gray-500 mb-6 max-w-md">
          All {{ itemsForUser.length }} feature{{ itemsForUser.length === 1 ? '' : 's' }} on this page 
          {{ itemsForUser.length === 1 ? 'is' : 'are' }} duplicate{{ itemsForUser.length === 1 ? '' : 's' }} and hidden by your filter.
          Try navigating to another page or disable "Hide duplicates" to see all features.
        </p>
        <div class="flex gap-3">
          <button
            v-if="adjustedHasPrevious"
            @click="previousPage"
            class="inline-flex items-center px-4 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
          >
            <ChevronLeftIcon class="w-4 h-4 mr-1" />
            Previous Page
          </button>
          <button
            v-if="adjustedHasNext"
            @click="nextPage"
            class="inline-flex items-center px-4 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
          >
            Next Page
            <ChevronRightIcon class="w-4 h-4 ml-1" />
          </button>
        </div>
      </div>
    </div>

    <!-- Feature Items -->
    <div v-else-if="itemsForUser.length > 0 && !loading.page" class="space-y-6">
      <div v-for="(entry, index) in filteredItemsForUser" :key="`item-${entry.originalIndex}`"
           :data-feature-index="(pagination.currentPage - 1) * pagination.pageSize + entry.originalIndex"
           :class="getItemClasses(entry.item, entry.originalIndex)">
        <!-- Button row - always fully visible -->
        <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 mb-4 sm:mb-6 relative z-20">
          <h3 class="text-base sm:text-lg font-semibold text-gray-900" :class="isItemSkipped(entry.item, entry.originalIndex) && !isItemHashDuplicate(entry.item) ? 'opacity-50' : ''">
            Feature {{ (pagination.currentPage - 1) * pagination.pageSize + entry.originalIndex + 1 }} (of {{ pagination.totalFeatures }})
          </h3>
          <div class="flex flex-wrap items-center gap-2 sm:space-x-2">
            <!-- Skip/Restore Button -->
            <button
                v-if="!isImported && !loading.importing"
                :class="isItemHashDuplicate(entry.item) ? 'relative z-20 inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-400 bg-gray-100 cursor-not-allowed' : (isItemSkipped(entry.item, entry.originalIndex) ? 'relative z-20 inline-flex items-center px-3 py-1.5 border border-transparent text-xs font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500' : 'relative z-20 inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-gray-500')"
                @click.stop="isItemHashDuplicate(entry.item) ? null : toggleSkipItem(entry.originalIndex)"
                :disabled="isItemHashDuplicate(entry.item)"
                :title="isItemHashDuplicate(entry.item) ? 'Hash duplicates cannot be restored' : (isItemSkipped(entry.item, entry.originalIndex) ? 'Restore this item' : 'Skip this item')"
                type="button"
                style="opacity: 1 !important;"
            >
              <CheckIcon v-if="isItemSkipped(entry.item, entry.originalIndex)" class="w-3 h-3 mr-1" />
              <XMarkIcon v-else class="w-3 h-3 mr-1" />
              {{ isItemSkipped(entry.item, entry.originalIndex) ? 'Restore' : 'Skip' }}
            </button>
            <button
                :class="isItemSkipped(entry.item, entry.originalIndex) ? 'inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-400 bg-gray-100 cursor-not-allowed' : 'inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500'"
                :disabled="isItemSkipped(entry.item, entry.originalIndex)"
                @click="showFeatureMap(entry.originalIndex)"
                title="View feature on map"
            >
              <MapIcon class="w-3 h-3 mr-1" />
              View on Map
            </button>
            <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-700">
              {{ entry.item.geometry.type }}
            </span>
          </div>
        </div>

        <!-- Duplicate Warnings - outside opacity div so they're always fully visible -->
        <div class="relative z-10">
          <DuplicateWarning type="feature_store_hash" :item="entry.item" />
          <DuplicateWarning type="feature_store_geometry" :item="entry.item" />
          <DuplicateWarning type="cross_queue_hash" :item="entry.item" />
          <DuplicateWarning type="cross_queue_geometry" :item="entry.item" />
        </div>

        <!-- Content area - can be greyed out for skipped or hash duplicate items -->
        <div :class="(isItemSkipped(entry.item, entry.originalIndex) && !isItemHashDuplicate(entry.item)) || isItemHashDuplicate(entry.item) ? 'opacity-50' : ''">
        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
          <!-- Name Field -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">Name</label>
            <div class="flex items-center space-x-2">
              <input
                  v-model="entry.item.properties.name"
                  :class="isItemDisabled(entry.item, entry.originalIndex) ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500'"
                  :disabled="isItemDisabled(entry.item, entry.originalIndex)"
                  :placeholder="originalItems[entry.originalIndex].properties.name"
              />
              <button
                  :disabled="!isItemEditable(entry.item, entry.originalIndex)"
                  class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white"
                  @click="resetNestedField(entry.originalIndex, 'properties', 'name')"
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
                  v-model="entry.item.properties.description"
                  :class="isItemDisabled(entry.item, entry.originalIndex) ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed resize-none' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 resize-none'"
                  :disabled="isItemDisabled(entry.item, entry.originalIndex)"
                  :placeholder="originalItems[entry.originalIndex].properties.description"
                  class="text-sm"
                  rows="4"
              ></textarea>
              <button
                  :disabled="!isItemEditable(entry.item, entry.originalIndex)"
                  class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white mt-1"
                  @click="resetNestedField(entry.originalIndex, 'properties', 'description')"
                  title="Reset to original description"
              >
                <ArrowPathIcon class="w-4 h-4" />
              </button>
            </div>
          </div>

          <!-- Left Column: Created Date + Style Controls -->
          <div class="space-y-4">
            <!-- Created Date Field -->
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-2">Created Date</label>
              <div class="flex items-center space-x-2">
                <input
                    :class="isItemDisabled(entry.item, entry.originalIndex) ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500'"
                    :disabled="isItemDisabled(entry.item, entry.originalIndex)"
                    :value="formatDateForInput(entry.item.properties.created)"
                    type="datetime-local"
                    @change="updateDate(entry.originalIndex, $event)"
                />
                <button
                    :disabled="!isItemEditable(entry.item, entry.originalIndex)"
                    class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white"
                    @click="resetNestedField(entry.originalIndex, 'properties', 'created')"
                    title="Reset to original date"
                >
                  <ArrowPathIcon class="w-4 h-4" />
                </button>
              </div>
            </div>

            <!-- Icon Selector (for points) -->
            <IconSelector
              v-if="isPointGeometry(entry.item)"
              :icon-url="getFeatureIconUrl(entry.item)"
              :original-icon-url="getFeatureIconUrlRaw(originalItems[entry.originalIndex])"
              :icon-color="entry.item.properties['marker-color']"
              :original-icon-color="originalItems[entry.originalIndex]?.properties?.['marker-color']"
              :disabled="isItemDisabled(entry.item, entry.originalIndex)"
              :show-remove="true"
              :show-reset="true"
              size="md"
              @icon-selected="handleIconSelected(entry.originalIndex, entry.item, $event)"
              @icon-removed="handleIconRemoved(entry.originalIndex, entry.item)"
              @icon-reset="handleIconReset(entry.originalIndex, entry.item, $event)"
              @icon-color-reset="handleIconColorReset(entry.originalIndex, entry.item)"
            />

            <!-- Icon Color (for points) -->
            <!-- Enabled for: default markers (no icon) OR system icons (recolorable) -->
            <!-- Disabled for: user icons or external URLs (non-recolorable) -->
            <div v-if="isPointGeometry(entry.item)">
              <label class="block text-sm font-medium text-gray-700 mb-2">Icon Color</label>
              <ColorPicker
                v-model="entry.item.properties['marker-color']"
                :disabled="isItemDisabled(entry.item, entry.originalIndex) || hasNonRecolorableIcon(entry.item)"
                size="md"
                @change="markItemAsEdited(entry.originalIndex)"
              />
            </div>

            <!-- Line Color -->
            <div v-if="isLineGeometry(entry.item)">
              <label class="block text-sm font-medium text-gray-700 mb-2">Line Color</label>
              <ColorPicker
                v-model="entry.item.properties.stroke"
                :disabled="isItemDisabled(entry.item, entry.originalIndex)"
                :show-reset="true"
                :can-reset="isItemEditable(entry.item, entry.originalIndex)"
                size="md"
                @change="handleStrokeColorChange(entry.originalIndex, entry.item)"
                @reset="resetNestedField(entry.originalIndex, 'properties', 'stroke')"
              />
            </div>

            <!-- Border Color -->
            <div v-if="isPolygonGeometry(entry.item)">
              <label class="block text-sm font-medium text-gray-700 mb-2">Border Color</label>
              <ColorPicker
                v-model="entry.item.properties.stroke"
                :disabled="isItemDisabled(entry.item, entry.originalIndex)"
                :show-reset="true"
                :can-reset="isItemEditable(entry.item, entry.originalIndex)"
                size="md"
                @change="handleStrokeColorChange(entry.originalIndex, entry.item)"
                @reset="resetNestedField(entry.originalIndex, 'properties', 'stroke')"
              />
            </div>
          </div>

          <!-- Right Column: Tags Section -->
          <div>
            <TagPicker
              v-model:tags="entry.item.properties.tags"
              :available-tags="availableUserTags"
              :system-tags="getSystemTags(entry.item)"
              :disabled="isItemDisabled(entry.item, entry.originalIndex)"
            />
            <div class="flex items-center space-x-2 mt-3">
              <button
                  :disabled="!isItemEditable(entry.item, entry.originalIndex) || isItemSkipped(entry.item, entry.originalIndex)"
                  class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white"
                  @click="resetTags(entry.originalIndex)"
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
        :has-next-page="adjustedHasNext"
        :has-previous-page="adjustedHasPrevious"
        :hide-duplicates="hideDuplicates"
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
        :total-pages="adjustedTotalPages"
        @previous-page="previousPage"
        @next-page="nextPage"
        @jump-to-page="goToPage"
        @show-map-preview="showMapPreview"
        @save-changes="saveChanges"
        @perform-import="performImport"
        @toggle-hide-duplicates="hideDuplicates = $event"
    />

    <div class="hidden">
      <!-- Load the queue to populate it. -->
      <ImportTable/>
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
import { formatDate } from "@/utils/dateUtils.js";
import {capitalizeFirstLetter} from "@/assets/js/string.js";
import {PROCESSING_MESSAGES} from "@/assets/js/constants/processing-messages.js";
import ImportTable from "@/components/import/parts/ImportTable.vue";
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
import TagPicker from "@/components/parts/TagPicker.vue";
import ImportProcessHeader from "@/components/import/parts/ImportProcessHeader.vue";
import ProcessingLogsPanel from "@/components/import/parts/ProcessingLogsPanel.vue";
import ImportSummaryStats from "@/components/import/parts/ImportSummaryStats.vue";
import GlobalOptionsPanel from "@/components/import/parts/GlobalOptionsPanel.vue";
import ColorPickerElement from "@/components/parts/ColorPickerElement.vue";
import IconSelector from "@/components/parts/IconSelector.vue";
import { DEFAULT_BULK_OPERATIONS, hasBulkOperationsConfigured, areBulkOperationsEqual, cloneBulkOperations } from "@/utils/bulkOperations.js";
import { CheckIcon, ExclamationCircleIcon, ArrowTopRightOnSquareIcon, DocumentIcon, ExclamationTriangleIcon, ArrowDownTrayIcon, ArrowUpTrayIcon, XMarkIcon, MapIcon, ArrowPathIcon, MagnifyingGlassIcon, RectangleStackIcon, ChevronLeftIcon, ChevronRightIcon } from '@heroicons/vue/24/outline';
import { connectWebSocket, sendWebSocketMessage, parseWebSocketMessage, shouldReconnect, getReconnectDelay } from '@/utils/import/websocketHandlers.js';
import { calculateTotalDuplicateCount, calculateHashDuplicateCount, markDuplicateFeatures, isItemDuplicate, isItemHashDuplicate, getFeatureId, isItemSkipped, isItemDisabled } from '@/utils/import/duplicateDetection.js';
import { getFeatureIconUrl, getFeatureIconUrlRaw, resolveIconUrl, hasCustomIcon, isSystemIcon, hasNonRecolorableIcon, handleIconError } from '@/utils/import/iconDetection.js';
import { calculateAdjustedTotalPages, calculateAdjustedHasNext, calculateAdjustedHasPrevious, calculateImportableCount, isValidPageNumber } from '@/utils/import/paginationUtils.js';
import { isPointGeometry, isLineGeometry, isPolygonGeometry, initializeFeatureDefaults, handleStrokeColorChange, getItemClasses, getLevelName, getLevelClass, filterWorkerLog } from '@/utils/import/featureProcessing.js';

export default {
  computed: {
    ...mapState(["userInfo", "userSettings"]),
    hasBulkOperationsConfigured() {
      return hasBulkOperationsConfigured(this.bulkOperations);
    },
    isValidPageNumber() {
      return this.pagination.gotoInput &&
          isValidPageNumber(this.pagination.gotoInput, this.adjustedTotalPages) &&
          this.pagination.gotoInput !== this.pagination.currentPage;
    },

    showNoFeaturesMessage() {
      return this.originalFilename != null && !this.processing.active && !this.loading.page && this.itemsForUser.length === 0;
    },

    hashDuplicateCount() {
      return calculateHashDuplicateCount(this.duplicates);
    },
    importableCount() {
      return calculateImportableCount(
        this.pagination.totalFeatures,
        this.hashDuplicateCount,
        this.skippedFeatureIds
      );
    },

    totalDuplicateCount() {
      return calculateTotalDuplicateCount(this.duplicates);
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
    },

    filteredItemsForUser() {
      // Filter out duplicates if hideDuplicates is enabled
      // Return array of {item, originalIndex} to preserve original index
      if (this.hideDuplicates) {
        return this.itemsForUser
          .map((item, originalIndex) => ({ item, originalIndex }))
          .filter(({ item }) => !this.isItemDuplicate(item));
      }
      return this.itemsForUser.map((item, originalIndex) => ({ item, originalIndex }));
    },

    // Computed properties to adjust pagination when hiding duplicates
    adjustedTotalPages() {
      return calculateAdjustedTotalPages(
        this.pagination.totalFeatures,
        this.totalDuplicateCount,
        this.pagination.pageSize,
        this.hideDuplicates,
        this.pagination.totalPages
      );
    },

    adjustedHasNext() {
      return calculateAdjustedHasNext(
        this.pagination.currentPage,
        this.adjustedTotalPages,
        this.hideDuplicates,
        this.pagination.hasNext
      );
    },

    adjustedHasPrevious() {
      return calculateAdjustedHasPrevious(
        this.pagination.currentPage,
        this.hideDuplicates,
        this.pagination.hasPrevious
      );
    },

    showEmptyPageMessage() {
      // Show a message when hiding duplicates results in an empty page
      return this.hideDuplicates && 
             this.itemsForUser.length > 0 && 
             this.filteredItemsForUser.length === 0 &&
             !this.loading.page;
    }
  },
  components: {
    Loader,
    ToggleButton,
    ImportTable: ImportTable,
    MapPreviewDialog,
    FeatureMapDialog,
    LogViewModal,
    ImportControls,
    BulkStylingModal,
    DuplicateWarning,
    TagPicker,
    ColorPicker: ColorPickerElement,
    IconSelector,
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
    RectangleStackIcon,
    ImportProcessHeader,
    ProcessingLogsPanel,
    ImportSummaryStats,
    GlobalOptionsPanel
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

      // Consolidated: Duplicates (4 types)
      duplicates: {
        featureStoreHash: [],
        featureStoreGeometry: [],
        crossQueueHash: [],
        crossQueueGeometry: []
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
      searchTimeout: null,

      // Duplicate hiding state
      hideDuplicates: false
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
    },
    '$route.query.scrollToIndex': {
      handler(globalIndex) {
        if (globalIndex !== undefined && globalIndex !== null) {
          const index = parseInt(globalIndex);
          if (!isNaN(index)) {
            this.scrollToGlobalIndex(index);
          }
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
      this.ws = connectWebSocket(this.currentId, {
        onOpen: this.onWebSocketOpen,
        onMessage: this.onWebSocketMessage,
        onClose: this.onWebSocketClose,
        onError: this.onWebSocketError
      });
    },

    onWebSocketOpen() {
      this.wsConnected = true;
      this.wsReconnectAttempts = 0;
    },

    onWebSocketMessage(event) {
      const message = parseWebSocketMessage(event);

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
        console.log('Item not found (404) - redirecting to import table');
        this.loading.redirecting = true;
        this.$router.replace('/import');
        return;
      }

      // Attempt to reconnect if conditions are met
      if (shouldReconnect(event, this.currentId, this.wsReconnectAttempts, this.maxReconnectAttempts)) {
        this.wsReconnectAttempts++;
        setTimeout(() => this.connectWebSocket(), getReconnectDelay());
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

        // Refresh the import table
        this.$store.dispatch('refreshImportTable');

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
          // Initialize default style properties if not present
          initializeFeatureDefaults(item);
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

      // Handle new duplicate structure from backend
      // Backend sends: duplicates object with 4 arrays
      if (data.duplicates) {
        this.duplicates = {
          featureStoreHash: data.duplicates.feature_store_hash || [],
          featureStoreGeometry: data.duplicates.feature_store_geometry || [],
          crossQueueHash: data.duplicates.cross_queue_hash || [],
          crossQueueGeometry: data.duplicates.cross_queue_geometry || []
        };

        // Mark all duplicate types on features
        markDuplicateFeatures(this.itemsForUser, this.duplicates);
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
        console.log('Item not found (404) - redirecting to import table');
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
      sendWebSocketMessage(this.ws, this.wsConnected, type, data);
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
              markDuplicateFeatures(this.itemsForUser, this.duplicates)
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
      return getLevelName(level);
    },
    getLevelClass(level) {
      return getLevelClass(level);
    },
    formatTimestamp(timestamp) {
      if (!timestamp) return '';
      return moment(timestamp).format('YYYY-MM-DD HH:mm:ss');
    },
    formatUploadDate: formatDate,
    getFeatureIconUrl(feature) {
      return getFeatureIconUrl(feature);
    },
    getFeatureIconUrlRaw(feature) {
      return getFeatureIconUrlRaw(feature);
    },
    resolveIconUrl(iconUrl) {
      return resolveIconUrl(iconUrl);
    },
    handleIconError(event) {
      handleIconError(event);
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
      const isHashDuplicate = isItemHashDuplicate(item);
      const isSkipped = isItemSkipped(item, index, this.skippedFeatureIds, this.pagination.currentPage, this.pagination.pageSize);
      return getItemClasses(item, isHashDuplicate, isSkipped);
    },
    getFeatureId(item, index) {
      return getFeatureId(item, index, this.pagination.currentPage, this.pagination.pageSize);
    },
    isItemSkipped(item, index) {
      return isItemSkipped(item, index, this.skippedFeatureIds, this.pagination.currentPage, this.pagination.pageSize);
    },
    isItemDuplicate(item) {
      return isItemDuplicate(item);
    },
    isItemHashDuplicate(item) {
      return isItemHashDuplicate(item);
    },
    isItemDisabled(item, index) {
      return isItemDisabled(item, index, this.isImported, this.loading.importing, this.skippedFeatureIds, this.pagination.currentPage, this.pagination.pageSize);
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
    // Geometry type detection methods for styling
    isPointGeometry(item) {
      return isPointGeometry(item);
    },
    isLineGeometry(item) {
      return isLineGeometry(item);
    },
    isPolygonGeometry(item) {
      return isPolygonGeometry(item);
    },
    hasCustomIcon(item) {
      return hasCustomIcon(item);
    },
    isSystemIcon(iconUrl) {
      return isSystemIcon(iconUrl);
    },
    hasNonRecolorableIcon(item) {
      return hasNonRecolorableIcon(item);
    },
    handleStrokeColorChange(index, item) {
      // Mark item as edited
      this.markItemAsEdited(index);
      
      // For polygons, automatically update fill color to match stroke with 10% opacity
      handleStrokeColorChange(item);
    },
    markItemAsEdited(index) {
      // This ensures the edit is tracked in the cache when saving
      // The cacheCurrentPageChanges method will handle persisting to editCache
      this.$forceUpdate();
    },
    handleIconSelected(index, item, event) {
      // Mark item as edited
      this.markItemAsEdited(index);
      
      // Set the icon URL in the item's properties
      const iconUrl = event.iconUrl;
      
      // Set icon in various possible property names for compatibility
      item.properties.icon = iconUrl;
      item.properties['icon-href'] = iconUrl;
      item.properties.iconUrl = iconUrl;
      item.properties.icon_url = iconUrl;
      
      // If it's a system icon, ensure marker-color is set for recoloring
      if (event.isSystemIcon) {
        // Initialize marker-color if not present
        if (!item.properties['marker-color']) {
          item.properties['marker-color'] = '#ff0000';
        }
      }
      
      this.$forceUpdate();
    },
    handleIconRemoved(index, item) {
      // Mark item as edited
      this.markItemAsEdited(index);
      
      // Remove icon from all possible property names
      item.properties.icon = '';
      item.properties['icon-href'] = '';
      item.properties.iconUrl = '';
      item.properties.icon_url = '';
      item.properties['marker-icon'] = '';
      item.properties['marker-symbol'] = '';
      item.properties.symbol = '';
      
      // Restore default marker-color if not present
      if (!item.properties['marker-color']) {
        item.properties['marker-color'] = '#ff0000';
      }
      
      this.$forceUpdate();
    },
    handleIconReset(index, item, originalIconUrl) {
      // Mark item as edited
      this.markItemAsEdited(index);
      
      // Restore original icon to all possible property names
      if (originalIconUrl) {
        item.properties.icon = originalIconUrl;
        item.properties['icon-href'] = originalIconUrl;
        item.properties.iconUrl = originalIconUrl;
        item.properties.icon_url = originalIconUrl;
      } else {
        // If original was null/empty, clear all icon properties
        item.properties.icon = '';
        item.properties['icon-href'] = '';
        item.properties.iconUrl = '';
        item.properties.icon_url = '';
        item.properties['marker-icon'] = '';
        item.properties['marker-symbol'] = '';
        item.properties.symbol = '';
      }
      
      // Reset marker-color based on icon type
      const originalItem = this.originalItems[index];
      if (!originalIconUrl) {
        // Default marker - reset color to original or default
        const originalColor = originalItem?.properties?.['marker-color'];
        item.properties['marker-color'] = originalColor || '#ff0000';
      } else if (this.isSystemIcon(originalIconUrl)) {
        // System icon - reset color to original or default
        const originalColor = originalItem?.properties?.['marker-color'];
        item.properties['marker-color'] = originalColor || '#ff0000';
      } else {
        // External/user icon - set color to black
        item.properties['marker-color'] = '#000000';
      }
      
      this.$forceUpdate();
    },
    handleIconColorReset(index, item) {
      // Mark item as edited
      this.markItemAsEdited(index);
      
      // Reset color to original value
      const originalItem = this.originalItems[index];
      const originalColor = originalItem?.properties?.['marker-color'];
      item.properties['marker-color'] = originalColor || '#ff0000';
      
      this.$forceUpdate();
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
      // Ensure properties.geojson_hash is set - backend requires it to match features
      // Preserve existing properties.geojson_hash if present, otherwise use top-level feature.id
      if (!properties.geojson_hash) {
        if (feature.id) {
          properties.geojson_hash = feature.id;
        }
        // Note: If neither properties.geojson_hash nor feature.id exists, the backend will skip this feature
        // This should not happen for valid features from the import table
      }
      // Extract only the allowed fields: geojson_hash, name, description, created, tags
      // geojson_hash is required, others are optional
      const partialUpdate = {
        properties: {
          geojson_hash: properties.geojson_hash
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
          // Use _prepareFeatureForBackend to ensure properties.geojson_hash is set for backend
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
                // Use _prepareFeatureForBackend to ensure properties.geojson_hash is set for backend
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
        // Send skipped_feature_ids which should ONLY contain geometry duplicates
        // Hash duplicates are always blocked by the backend and should NOT be in this list
        const skippedFeatureIdsArray = Array.from(this.skippedFeatureIds).filter(id => !id.startsWith('index_'));

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
      markDuplicateFeatures(this.itemsForUser, this.duplicates);
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
          // Store the raw ops (before normalization) as original state
          // This preserves which keys were explicitly set vs not set
          this.originalBulkOperations = ops && typeof ops === 'object' ? { ...ops } : {};
        } else {
          // No bulk operations found, use empty object (not DEFAULT_BULK_OPERATIONS)
          // This allows us to detect when keys are added (e.g., pointIcon: null for default icon)
          this.bulkOperations = cloneBulkOperations({});
          // Store empty object as original (no keys set)
          this.originalBulkOperations = {};
        }
      } catch (error) {
        // Log error and use empty object (not DEFAULT_BULK_OPERATIONS)
        // This allows us to detect when keys are added (e.g., pointIcon: null for default icon)
        console.error('Error loading bulk operations:', error);
        this.bulkOperations = cloneBulkOperations({});
        // Store empty object as original (no keys set)
        this.originalBulkOperations = {};
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
          // Update original state to reflect saved state (store raw object to preserve key presence)
          this.originalBulkOperations = bulkData && typeof bulkData === 'object' ? { ...bulkData } : {};
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
            if (item && item.properties && item.properties.geojson_hash) {
              featureId = item.properties.geojson_hash;
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
        const featureHash = result.feature?.properties?.geojson_hash;
        if (!featureHash) {
          console.warn('Search result missing feature hash');
          this.clearSearch();
          return;
        }
        
        // Check if we're already on the correct page
        const isAlreadyOnPage = this.pagination.currentPage === result.page;
        
        if (!isAlreadyOnPage) {
          // Navigate to the page from the search result
          await this.goToPage(result.page);
          
          // Wait for the page to finish loading
          await this.waitForPageLoad();
          await this.waitForItems();
        }
        
        // Wait for DOM to be fully updated
        await this.$nextTick();
        await new Promise(resolve => setTimeout(resolve, isAlreadyOnPage ? 0 : 100));
        
        // Find feature by hash in current page
        const localIndex = this.itemsForUser.findIndex(item =>
          item.properties && item.properties.geojson_hash === featureHash
        );
        
        if (localIndex >= 0) {
          const globalIndex = (this.pagination.currentPage - 1) * this.pagination.pageSize + localIndex;
          this.scrollToFeature(globalIndex);
        } else {
          console.warn(`Feature with hash ${featureHash} not found on page ${result.page}`);
        }
        
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
        item.properties && item.properties.geojson_hash === hash
      );

      if (featureIndex >= 0) {
        const globalIndex = (this.pagination.currentPage - 1) * this.pagination.pageSize + featureIndex;
        this.scrollToFeature(globalIndex);
      } else {
        console.warn(`Feature with hash ${hash} not found on current page`);
      }
    },
    async scrollToGlobalIndex(globalIndex) {
      // Calculate which page the feature is on
      const targetPage = Math.floor(globalIndex / this.pagination.pageSize) + 1;
      console.log(`Target page: ${targetPage}, current page: ${this.pagination.currentPage}`);

      // Navigate to the page if not already there
      if (this.pagination.currentPage !== targetPage) {
        console.log(`Navigating to page ${targetPage}`);
        await this.goToPage(targetPage);
        await this.waitForPageLoad();
        await this.waitForItems();
      }

      // Wait for DOM to be fully updated
      await this.$nextTick();

      // Additional wait to ensure rendering is complete
      await new Promise(resolve => setTimeout(resolve, 300));

      // Scroll to the feature with retry logic
      let attempts = 0;
      const maxAttempts = 5;
      const attemptScroll = () => {
        attempts++;
        console.log(`Scroll attempt ${attempts} for global index ${globalIndex}`);

        const element = document.querySelector(`[data-feature-index="${globalIndex}"]`);
        if (element) {
          console.log(`Found element for index ${globalIndex}, scrolling`);
          element.scrollIntoView({
            behavior: 'smooth',
            block: 'center'
          });

          // Highlight the element
          element.classList.add('ring-2', 'ring-blue-500', 'ring-offset-2');
          setTimeout(() => {
            element.classList.remove('ring-2', 'ring-blue-500', 'ring-offset-2');
          }, 2000);
        } else {
          console.warn(`Element not found for index ${globalIndex} (attempt ${attempts}/${maxAttempts})`);
          if (attempts < maxAttempts) {
            setTimeout(attemptScroll, 200);
          } else {
            console.error(`Failed to find element after ${maxAttempts} attempts`);
          }
        }
      };

      attemptScroll();
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

    // Check for scrollToIndex query parameter
    if (this.$route.query.scrollToIndex) {
      const globalIndex = parseInt(this.$route.query.scrollToIndex);
      if (!isNaN(globalIndex)) {
        this.scrollToGlobalIndex(globalIndex);
      }
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