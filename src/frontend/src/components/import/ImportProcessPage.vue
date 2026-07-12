<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <ImportProcessHeader
      :original-filename="originalFilename ?? undefined"
      :upload-timestamp="uploadTimestamp ?? undefined"
      :is-imported="isImported"
      :import-item-id="typeof currentId === 'number' ? currentId : undefined"
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
      :is-loading="loadingPage"
    />

    <!-- Loading State for Initial Page Load and Post-Processing -->
    <Loader
      v-if="(originalFilename == null && !loadingPage) || (processing.active && processing.progress === null)"
      :message="loadingMessage"
    />

    <!-- Search Box -->
    <div v-if="itemsForUser.length > 0 && !loadingPage && !processing.active" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <div class="flex flex-col gap-4">
        <div class="flex items-center gap-3">
          <div class="flex-1 relative">
            <input
                v-model="searchQuery"
                :disabled="loadingPage || isSearching"
                type="text"
                placeholder="Search features..."
                class="block w-full px-4 py-2 pl-10 border-2 border-blue-500 rounded-md focus:ring-blue-500 focus:border-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                @input="handleSearchInput"
            />
            <MagnifyingGlassIcon class="absolute left-3 top-1/2 transform -translate-y-1/2 h-5 w-5 text-gray-400" />
          </div>
          <BaseButton
              v-if="searchQuery"
              @click="clearSearch"
              variant="primary"
              color="blue"
              size="sm"
              title="Clear Search"
          >
            <XMarkIcon class="w-4 h-4" />
          </BaseButton>
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
                  <BaseButton
                      @click="goToSearchResult(result)"
                      variant="primary"
                      color="blue"
                      size="xs"
                      no-wrap
                      title="Jump to Feature"
                  >
                    Jump to Feature
                  </BaseButton>
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
      :is-loading="loadingPage"
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
        :has-features="itemsForUser.length > 0"
        :has-next-page="adjustedHasNext"
        :has-previous-page="adjustedHasPrevious"
        :hide-duplicates="hideDuplicates"
        :importable-count="importableCount"
        :is-imported="isImported"
        :is-importing="loading.importing"
        :is-loading-page="loadingPage"
        :is-saving="loading.saving"
        :lock-buttons="lockButtons"
        :page-size="pagination.pageSize"
        :save-status="saveStatus ?? undefined"
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
    <div v-if="loadingPage" class="space-y-6">
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

    <!-- Feature Items (virtualized: cards have variable height depending on geometry type / duplicate warnings) -->
    <DynamicScroller
      v-else-if="itemsForUser.length > 0 && !loadingPage"
      ref="featureScrollerRef"
      :items="filteredItemsForUser"
      key-field="originalIndex"
      :min-item-size="360"
      class="feature-scroller"
      v-slot="{ item: entry, index, active }"
    >
      <DynamicScrollerItem :item="entry" :active="active" :index="index" tag="div" class="pb-6">
        <div :data-feature-index="(pagination.currentPage - 1) * pagination.pageSize + entry.originalIndex"
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
                  @click.stop="handleToggleSkipItem(entry.originalIndex, entry.item)"
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
                  title="View Feature on Map"
              >
                <MapIcon class="w-3 h-3 mr-1" />
                View on Map
              </button>
              <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-700">
                {{ formatGeometryTypeForDisplay(entry.item.geometry.type) }}
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
                    :placeholder="originalItems[entry.originalIndex]?.properties.name"
                />
                <button
                    :disabled="!isItemEditable(entry.item, entry.originalIndex)"
                    class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white"
                    @click="resetNestedField(entry.originalIndex, 'properties', 'name')"
                    title="Reset to Original Name"
                >
                  <ArrowPathIcon class="w-4 h-4" />
                </button>
              </div>
              <p v-if="entry.item.properties.original_name" class="mt-1 text-xs text-gray-500">
                Original name: <span class="italic">{{ entry.item.properties.original_name }}</span>
              </p>
            </div>

            <!-- Description Field -->
            <div class="md:col-span-2">
              <label class="block text-sm font-medium text-gray-700 mb-2">Description</label>
              <div class="flex items-start space-x-2">
                <textarea
                    v-model="entry.item.properties.description"
                    :class="isItemDisabled(entry.item, entry.originalIndex) ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed resize-none' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 resize-none'"
                    :disabled="isItemDisabled(entry.item, entry.originalIndex)"
                    :placeholder="originalItems[entry.originalIndex]?.properties.description"
                    class="text-sm"
                    rows="4"
                ></textarea>
                <button
                    :disabled="!isItemEditable(entry.item, entry.originalIndex)"
                    class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-white mt-1"
                    @click="resetNestedField(entry.originalIndex, 'properties', 'description')"
                    title="Reset to Original Description"
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
                      title="Reset to Original Date"
                  >
                    <ArrowPathIcon class="w-4 h-4" />
                  </button>
                </div>
              </div>

              <!-- Icon Selector (for points) -->
              <IconSelector
                v-if="isPointGeometry(entry.item)"
                :icon-url="getFeatureIconUrl(entry.item) ?? undefined"
                :original-icon-url="getFeatureIconUrlRaw(originalItems[entry.originalIndex]) ?? undefined"
                :icon-color="entry.item.properties['marker-color']"
                :original-icon-color="originalItems[entry.originalIndex]?.properties?.['marker-color'] ?? undefined"
                :disabled="isItemDisabled(entry.item, entry.originalIndex)"
                :show-remove="true"
                :show-reset="true"
                size="md"
                @icon-selected="handleIconSelected(entry.item, $event)"
                @icon-removed="handleIconRemoved(entry.item)"
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
                  @change="handleStrokeColorChangeForItem(entry.item)"
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
                  @change="handleStrokeColorChangeForItem(entry.item)"
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
            </div>
          </div>
          </div>
        </div>
      </DynamicScrollerItem>
    </DynamicScroller>

    <!-- Controls (Bottom) -->
    <ImportControls
        v-if="!loadingPage"
        :current-page="pagination.currentPage"
        :duplicate-count="totalDuplicateCount"
        :file-duplicate="fileDuplicate"
        :has-features="itemsForUser.length > 0"
        :has-next-page="adjustedHasNext"
        :has-previous-page="adjustedHasPrevious"
        :hide-duplicates="hideDuplicates"
        :importable-count="importableCount"
        :is-imported="isImported"
        :is-importing="loading.importing"
        :is-loading-page="loadingPage"
        :is-saving="loading.saving"
        :lock-buttons="lockButtons"
        :page-size="pagination.pageSize"
        :save-status="saveStatus ?? undefined"
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
        :features="previewFeatures"
        :filename="originalFilename ?? undefined"
        :is-open="dialogs.mapPreview"
        @close="closeMapPreview"
    />

    <!-- Feature Map Dialog -->
    <FeatureMapDialog
        :features="previewFeatures"
        :filename="originalFilename ?? undefined"
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
        :is-open="bulkOperationsModalOpen"
        :available-tags="availableUserTags"
        :current-bulk-ops="bulkOperations"
        :original-bulk-ops="originalBulkOperations"
        @close="closeBulkOperationsModal"
        @apply="updateBulkOperations"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router';
import { useStore } from 'vuex';
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller';
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css';

import type { RootState } from '@/assets/js/store';
import type { ImportFeatureItem } from '@/assets/js/types/import-types';
import type { GeoJsonFeature } from '@/types/geospatial';

import Loader from '@/components/parts/Loader.vue';
import BaseButton from '@/components/parts/BaseButton.vue';
import MapPreviewDialog from '@/components/import/parts/MapPreviewDialog.vue';
import FeatureMapDialog from '@/components/import/parts/FeatureMapDialog.vue';
import LogViewModal from '@/components/import/parts/LogViewModal.vue';
import ImportControls from '@/components/import/parts/ImportControls.vue';
import BulkStylingModal from '@/components/import/parts/BulkStylingModal.vue';
import DuplicateWarning from '@/components/import/parts/DuplicateWarning.vue';
import TagPicker from '@/components/parts/TagPicker.vue';
import ImportProcessHeader from '@/components/import/parts/ImportProcessHeader.vue';
import ProcessingLogsPanel from '@/components/import/parts/ProcessingLogsPanel.vue';
import ImportSummaryStats from '@/components/import/parts/ImportSummaryStats.vue';
import GlobalOptionsPanel from '@/components/import/parts/GlobalOptionsPanel.vue';
import ColorPicker from '@/components/parts/ColorPickerElement.vue';
import IconSelector from '@/components/parts/IconSelector.vue';
import ImportTable from '@/components/import/parts/ImportTable.vue';
import {
  CheckIcon,
  ExclamationTriangleIcon,
  XMarkIcon,
  MapIcon,
  ArrowPathIcon,
  MagnifyingGlassIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
} from '@heroicons/vue/24/outline';

import { ImportStatusSocket } from '@/assets/js/websocket/ImportStatusSocket';
import { useImportProcessData, type RawImportPagePayload, type SearchResultMatch } from '@/composables/useImportProcessData';
import { useImportFeatureEditing } from '@/composables/useImportFeatureEditing';
import { useBulkOperations } from '@/composables/useBulkOperations';

import { formatGeometryTypeForDisplay } from '@/utils/geometryTypeFormatter';
import { getItemClasses as getItemClassesUtil } from '@/utils/import/featureProcessing';
import { getFeatureIconUrl, getFeatureIconUrlRaw, hasNonRecolorableIcon } from '@/utils/import/iconDetection';
import { toastApiError } from '@/utils/apiError';
import { PROCESSING_MESSAGES } from '@/assets/js/constants/processing-messages';

const props = defineProps<{ id: string }>();

const route = useRoute();
const router = useRouter();
const store = useStore<RootState>();

// ---------------------------------------------------------------------------
// Local, page-only state (data fetching/pagination, per-feature editing, and
// bulk operations state live in the composables below).
// ---------------------------------------------------------------------------

const currentId = ref<string | number | null>(props.id);
const originalFilename = ref<string | null>(null);
const uploadTimestamp = ref<string | null>(null);
const isImported = ref(false);
const lockButtons = ref(false);
const importCustomIcons = ref(true);
const statusMessage = ref<string | null>(null);
const statusDetail = ref<string | null>(null);
const waitingForImportCompletion = ref(false);
const saveStatus = ref<'success' | 'error' | null>(null);
let saveStatusTimeout: ReturnType<typeof setTimeout> | null = null;

const fileDuplicate = reactive<{ status: string | null; originalFilename: string | null }>({
  status: null,
  originalFilename: null,
});

const loading = reactive({
  logs: true,
  saving: false,
  importing: false,
  redirecting: false,
  recheckingDuplicates: false,
});

const processing = reactive<{ active: boolean; message: string; progress: number | null }>({
  active: false,
  message: '',
  progress: null,
});

interface LogEntry {
  id: number;
  level?: number;
  msg?: string;
  source?: string;
  timestamp?: string;
}

const workerLog = ref<LogEntry[]>([]);
let lastLogId: number | null = null;

const dialogs = reactive({
  mapPreview: false,
  featureMap: { isOpen: false, selectedIndex: 0 },
  logs: false,
});

interface ScrollerInstance {
  scrollToItem: (index: number) => void;
}
const featureScrollerRef = ref<ScrollerInstance | null>(null);

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

const importStatusSocket = new ImportStatusSocket();

function requestPageOverSocket(page: number, pageSize: number): void {
  importStatusSocket.send('request_page', { page, page_size: pageSize });
}

const importData = useImportProcessData({
  importId: currentId,
  requestPage: requestPageOverSocket,
  onUnparsableFile: () => {
    processing.active = false;
    processing.message = PROCESSING_MESSAGES.PROCESSING_FAILED;
    processing.progress = null;
  },
});
const {
  msg,
  loadingPage,
  itemsForUser,
  originalItems,
  pagination,
  duplicates,
  hideDuplicates,
  skippedFeatureIds,
  editCache,
  totalDuplicateCount,
  importableCount,
  adjustedTotalPages,
  adjustedHasNext,
  adjustedHasPrevious,
  filteredItemsForUser,
  showEmptyPageMessage,
  searchQuery,
  searchResults,
  totalSearchMatches,
  isSearching,
  handleSearchInput,
  clearSearch,
  cacheCurrentPageChanges,
  nextPage,
  previousPage,
  goToPage,
  toggleSkipItem,
  isItemSkipped,
  isItemDisabled: isItemDisabledFromData,
  isItemHashDuplicate,
} = importData;

/**
 * `ImportFeatureItem.type` holds the geometry-type string (e.g. `'Point'`), not the GeoJSON
 * `Feature` discriminant `MapPreviewDialog`/`FeatureMapDialog` expect, so adapt rather than cast.
 */
const previewFeatures = computed<GeoJsonFeature[]>(() =>
  itemsForUser.value.map((item) => ({
    type: 'Feature',
    geometry: item.geometry as GeoJsonFeature['geometry'],
    properties: item.properties,
    geojson_hash: item.properties.geojson_hash,
  })),
);

const featureEditing = useImportFeatureEditing({
  importId: currentId,
  itemsForUser,
  originalItems,
  pagination,
  editCache,
  duplicates,
  skippedFeatureIds,
});
const {
  availableUserTags,
  fetchUserTags,
  getSystemTags,
  isPointGeometry,
  isLineGeometry,
  isPolygonGeometry,
  resetNestedField,
  updateDate,
  formatDateForInput,
  handleIconSelected,
  handleIconRemoved,
  handleIconReset,
  handleIconColorReset,
  handleStrokeColorChangeForItem,
  getChangedFeatures,
  hasFeatureChanges,
  saveFeatures,
  saveSkipState,
  requestImport: requestImportApi,
  requestRecheckDuplicates,
} = featureEditing;

const bulkOps = useBulkOperations(currentId);
const {
  isModalOpen: bulkOperationsModalOpen,
  bulkOperations,
  originalBulkOperations,
  hasBulkOperationsConfigured,
  hasBulkOperationsChanged,
  loadBulkOperations,
  updateBulkOperations,
  saveBulkOperations,
  openModal: openBulkOperationsModal,
  closeModal: closeBulkOperationsModal,
  reset: resetBulkOperations,
} = bulkOps;

// ---------------------------------------------------------------------------
// Page-level computed
// ---------------------------------------------------------------------------

interface UserSettingsGetterShape {
  import?: { show_debug_logs?: boolean };
}
interface RootGetters {
  'userSettings/userSettings': UserSettingsGetterShape | null;
}

const showDebugLogs = computed<boolean>(() => {
  const getters = store.getters as RootGetters;
  return getters['userSettings/userSettings']?.import?.show_debug_logs === true;
});

const filteredWorkerLog = computed<LogEntry[]>(() => {
  if (!showDebugLogs.value) {
    return workerLog.value.filter((log) => log.level !== 10);
  }
  return workerLog.value;
});

const loadingMessage = computed<string>(() => {
  if (statusMessage.value) {
    return statusDetail.value ? `${statusMessage.value} ${statusDetail.value}` : statusMessage.value;
  }
  return 'Loading...';
});

const showNoFeaturesMessage = computed<boolean>(() =>
  originalFilename.value != null && !processing.active && !loadingPage.value && itemsForUser.value.length === 0,
);

// ---------------------------------------------------------------------------
// Per-item helpers that need page-level state (isImported / loading.importing)
// ---------------------------------------------------------------------------

function isItemDisabled(item: ImportFeatureItem | null | undefined, index: number): boolean {
  return isItemDisabledFromData(item, index, isImported.value, loading.importing);
}

function isItemEditable(item: ImportFeatureItem | null | undefined, index: number): boolean {
  return !isItemDisabled(item, index);
}

function getItemClasses(item: ImportFeatureItem, index: number): string {
  return getItemClassesUtil(item, isItemHashDuplicate(item), isItemSkipped(item, index));
}

function handleToggleSkipItem(index: number, item: ImportFeatureItem): void {
  if (isItemHashDuplicate(item)) return;
  toggleSkipItem(index);
}

function truncateDescription(description: string | null | undefined): string {
  if (!description) return '';
  const maxLength = 100;
  if (description.length <= maxLength) return description;
  return `${description.substring(0, maxLength)}...`;
}

function hasUnsavedChanges(): boolean {
  return hasFeatureChanges() || hasBulkOperationsChanged.value;
}

// ---------------------------------------------------------------------------
// Save / import / recheck orchestration (coordinates the feature-editing and
// bulk-operations composables; this is genuinely page-level glue).
// ---------------------------------------------------------------------------

async function saveChangesInternal(): Promise<{ changedCount: number }> {
  cacheCurrentPageChanges();
  const changedFeatures = getChangedFeatures();
  const bulkOpsChanged = hasBulkOperationsChanged.value;

  if (bulkOpsChanged) {
    await saveBulkOperations(originalBulkOperations.value);
  }

  await saveSkipState();

  if (changedFeatures.length === 0 && !bulkOpsChanged) {
    return { changedCount: 0 };
  }

  const result = await saveFeatures(changedFeatures);
  return { changedCount: result.updatedCount };
}

async function saveChanges(): Promise<void> {
  lockButtons.value = true;
  loading.saving = true;
  if (saveStatusTimeout) {
    clearTimeout(saveStatusTimeout);
    saveStatusTimeout = null;
  }
  saveStatus.value = null;

  try {
    await saveChangesInternal();
    lockButtons.value = false;
    loading.saving = false;
    saveStatus.value = 'success';
    saveStatusTimeout = setTimeout(() => {
      saveStatus.value = null;
      saveStatusTimeout = null;
    }, 2000);
  } catch (error) {
    loading.saving = false;
    // Buttons stay locked (permanently) so the user doesn't lose more work by retrying blindly.
    saveStatus.value = 'error';
    toastApiError(error, 'Error saving changes. Please reload the page to try again');
  }
}

async function performImport(): Promise<void> {
  lockButtons.value = true;
  loading.importing = true;

  try {
    await saveChangesInternal();
  } catch (saveError) {
    toastApiError(saveError, 'Error saving changes before import');
    lockButtons.value = false;
    loading.importing = false;
    waitingForImportCompletion.value = false;
    return;
  }

  try {
    // Set the flag before making the request so WebSocket events are handled correctly
    // (WS messages can arrive before the request itself resolves).
    waitingForImportCompletion.value = true;
    await requestImportApi(importCustomIcons.value);
    // Server responds immediately; completion arrives later via `item_completed`/`item_failed`.
  } catch (error) {
    toastApiError(error, 'Error performing import');
    lockButtons.value = false;
    loading.importing = false;
    waitingForImportCompletion.value = false;
  }
}

async function recheckDuplicates(): Promise<void> {
  lockButtons.value = true;
  loading.recheckingDuplicates = true;
  try {
    await requestRecheckDuplicates();
    // Refresh page data + logs via the WebSocket now that duplicates have been recomputed.
    importStatusSocket.send('refresh', {});
  } catch (error) {
    toastApiError(error, 'Error rechecking duplicates');
  } finally {
    lockButtons.value = false;
    loading.recheckingDuplicates = false;
  }
}

// ---------------------------------------------------------------------------
// Scrolling / highlighting a specific feature (DOM-only concern that stays at
// the component level). Adapted to virtualization: the scroller is asked to
// bring the target row into view (via its own `scrollToItem`) before we look
// for the now-rendered DOM node to highlight it.
// ---------------------------------------------------------------------------

function highlightFeatureElement(globalIndex: number): void {
  const element = document.querySelector(`[data-feature-index="${globalIndex}"]`);
  if (!element) return;
  element.classList.add('ring-2', 'ring-blue-500', 'ring-offset-2');
  setTimeout(() => {
    element.classList.remove('ring-2', 'ring-blue-500', 'ring-offset-2');
  }, 2000);
}

function scrollToLocalIndex(localIndex: number, globalIndex: number, attempt = 0): void {
  const maxAttempts = 5;
  featureScrollerRef.value?.scrollToItem(localIndex);
  void nextTick(() => {
    setTimeout(() => {
      const element = document.querySelector(`[data-feature-index="${globalIndex}"]`);
      if (element) {
        highlightFeatureElement(globalIndex);
      } else if (attempt < maxAttempts) {
        scrollToLocalIndex(localIndex, globalIndex, attempt + 1);
      }
    }, 200);
  });
}

function scrollToOriginalIndex(originalIndex: number): void {
  const localIndex = filteredItemsForUser.value.findIndex((entry) => entry.originalIndex === originalIndex);
  if (localIndex === -1) return;
  const globalIndex = (pagination.currentPage - 1) * pagination.pageSize + originalIndex;
  scrollToLocalIndex(localIndex, globalIndex);
}

function scrollToFeature(globalIndex: number): void {
  scrollToOriginalIndex(globalIndex - (pagination.currentPage - 1) * pagination.pageSize);
}

function scrollToFeatureByHash(hash: string): void {
  const featureIndex = itemsForUser.value.findIndex((item) => item.properties.geojson_hash === hash);
  if (featureIndex >= 0) {
    scrollToOriginalIndex(featureIndex);
  } else {
    console.warn(`Feature with hash ${hash} not found on current page`);
  }
}

function waitForPageLoad(): Promise<void> {
  return new Promise((resolve) => {
    if (!loadingPage.value) {
      resolve();
      return;
    }
    const checkInterval = setInterval(() => {
      if (!loadingPage.value) {
        clearInterval(checkInterval);
        resolve();
      }
    }, 50);
    setTimeout(() => {
      clearInterval(checkInterval);
      resolve();
    }, 5000);
  });
}

function waitForItems(): Promise<void> {
  return new Promise((resolve) => {
    if (itemsForUser.value.length > 0) {
      void nextTick(() => setTimeout(() => { resolve(); }, 200));
      return;
    }
    const checkInterval = setInterval(() => {
      if (itemsForUser.value.length > 0) {
        clearInterval(checkInterval);
        void nextTick(() => setTimeout(() => { resolve(); }, 200));
      }
    }, 50);
    setTimeout(() => {
      clearInterval(checkInterval);
      resolve();
    }, 5000);
  });
}

async function scrollToGlobalIndex(globalIndex: number): Promise<void> {
  const targetPage = Math.floor(globalIndex / pagination.pageSize) + 1;
  if (pagination.currentPage !== targetPage) {
    await goToPage(targetPage);
    await waitForPageLoad();
    await waitForItems();
  }
  await nextTick();
  await new Promise((resolve) => setTimeout(resolve, 300));
  scrollToFeature(globalIndex);
}

async function goToSearchResult(result: SearchResultMatch): Promise<void> {
  if (result.page < 1 || result.page > pagination.totalPages) return;

  const featureHash = result.feature.properties?.geojson_hash;
  if (!featureHash) {
    console.warn('Search result missing feature hash');
    clearSearch();
    return;
  }

  const isAlreadyOnPage = pagination.currentPage === result.page;
  if (!isAlreadyOnPage) {
    await goToPage(result.page);
    await waitForPageLoad();
    await waitForItems();
  }

  await nextTick();
  await new Promise((resolve) => setTimeout(resolve, isAlreadyOnPage ? 0 : 100));

  const localIndex = itemsForUser.value.findIndex((item) => item.properties.geojson_hash === featureHash);
  if (localIndex >= 0) {
    const globalIndex = (pagination.currentPage - 1) * pagination.pageSize + localIndex;
    scrollToFeature(globalIndex);
  } else {
    console.warn(`Feature with hash ${featureHash} not found on page ${result.page}`);
  }

  clearSearch();
}

// ---------------------------------------------------------------------------
// Dialog helpers
// ---------------------------------------------------------------------------

function showMapPreview(): void {
  dialogs.mapPreview = true;
}
function closeMapPreview(): void {
  dialogs.mapPreview = false;
}
function showFeatureMap(featureIndex: number): void {
  dialogs.featureMap.selectedIndex = featureIndex;
  dialogs.featureMap.isOpen = true;
}
function closeFeatureMap(): void {
  dialogs.featureMap.isOpen = false;
}
function closeLogModal(): void {
  dialogs.logs = false;
}

// ---------------------------------------------------------------------------
// WebSocket wiring (`ImportStatusSocket`) -- per-item processing/page/log
// updates. Handlers are registered once; `connectWebSocket` just (re)opens
// the connection for the current `currentId`.
// ---------------------------------------------------------------------------

interface FileDuplicateInfo {
  status: string | null;
  originalFilename?: string | null;
}

interface InitialStatePayload {
  original_filename?: string | null;
  timestamp?: string | null;
  imported?: boolean;
  processing?: boolean;
  file_duplicate?: FileDuplicateInfo | null;
  job_details?: { message?: string; progress?: number };
  unparsable?: boolean;
  features?: RawImportPagePayload;
  logs?: LogEntry[];
}

interface StatusUpdatePayload {
  message?: string;
  progress?: number;
}

interface StatusMessagePayload {
  message?: string;
  detail?: string;
}

interface DuplicateSkippedFeature {
  name?: string;
  hash?: string;
  queue_item_filename?: string;
}

interface ItemCompletedPayload {
  message?: string;
  duplicates_skipped?: { hash?: DuplicateSkippedFeature[]; coord?: DuplicateSkippedFeature[] };
}

interface ItemFailedPayload {
  message?: string;
  error_message?: string;
}

interface LogsPayload {
  logs?: LogEntry[];
  after_id?: number | null;
}

interface WsErrorPayload {
  code?: number;
  message?: string;
  file_duplicate?: FileDuplicateInfo | null;
}

function handleInitialState(data: InitialStatePayload): void {
  originalFilename.value = data.original_filename ?? null;
  uploadTimestamp.value = data.timestamp ?? null;
  isImported.value = data.imported ?? false;
  processing.active = data.processing ?? false;
  fileDuplicate.status = data.file_duplicate?.status ?? null;
  fileDuplicate.originalFilename = data.file_duplicate?.originalFilename ?? null;

  statusMessage.value = null;
  statusDetail.value = null;

  if (data.job_details) {
    processing.message = data.job_details.message ?? 'Processing file...';
    processing.progress = data.job_details.progress ?? 0;
  }

  if (data.unparsable) {
    processing.active = false;
    processing.message = PROCESSING_MESSAGES.PROCESSING_FAILED;
    processing.progress = null;
  }

  if (data.features) {
    importData.handlePageData(data.features);
  }

  if (data.logs) {
    const lastIncomingLogId = data.logs.length > 0 ? data.logs[data.logs.length - 1].id : null;
    const shouldReplace = workerLog.value.length === 0 ||
      (lastIncomingLogId != null && lastLogId != null && lastIncomingLogId > lastLogId);
    if (shouldReplace) {
      workerLog.value = data.logs;
      lastLogId = lastIncomingLogId;
    }
  }

  loading.logs = false;
  loadingPage.value = false;
}

function handleStatusUpdate(data: StatusUpdatePayload): void {
  processing.message = data.message ?? 'Processing file...';
  processing.progress = data.progress ?? 0;
}

function handleStatusMessage(data: StatusMessagePayload): void {
  if (data.message) {
    statusMessage.value = data.message;
    statusDetail.value = data.detail ?? null;
  }
}

function handleLogAdded(data: LogEntry): void {
  const existingLog = workerLog.value.find((log) => log.id === data.id);
  if (existingLog) return;
  workerLog.value.push(data);
  lastLogId = data.id;
}

function removeBeforeUnloadHandler(): void {
  window.removeEventListener('beforeunload', handleBeforeUnload);
}

function handleItemCompleted(data: ItemCompletedPayload): void {
  processing.active = false;
  processing.message = 'Loading...';
  processing.progress = null;

  // Log skipped duplicates to console (silent, no user notification).
  if (data.duplicates_skipped) {
    const hashDups = data.duplicates_skipped.hash ?? [];
    const coordDups = data.duplicates_skipped.coord ?? [];

    if (hashDups.length > 0) {
      console.log(`Skipped ${hashDups.length} hash duplicate(s):`);
      hashDups.forEach((feature) => {
        console.log(feature.queue_item_filename
          ? `  - ${feature.name ?? ''} (hash: ${feature.hash ?? ''}) from "${feature.queue_item_filename}"`
          : `  - ${feature.name ?? ''} (hash: ${feature.hash ?? ''})`);
      });
    }
    if (coordDups.length > 0) {
      console.log(`Skipped ${coordDups.length} coordinate duplicate(s):`);
      coordDups.forEach((feature) => {
        console.log(`  - ${feature.name ?? ''} (hash: ${feature.hash ?? ''})`);
      });
    }
  }

  if (waitingForImportCompletion.value) {
    waitingForImportCompletion.value = false;
    lockButtons.value = false;
    loading.importing = false;
    removeBeforeUnloadHandler();
    loading.redirecting = true;
    window.alert(`Import successful: ${data.message ?? 'Import completed successfully'}`);
    void router.replace('/import');
    return;
  }

  // Keep processing active to show the unified loading spinner while the refreshed page loads.
  processing.active = true;
  importStatusSocket.send('refresh', {});
}

function handleItemFailed(data: ItemFailedPayload): void {
  processing.active = false;
  processing.message = 'Processing failed';
  processing.progress = null;

  if (waitingForImportCompletion.value) {
    waitingForImportCompletion.value = false;
    lockButtons.value = false;
    loading.importing = false;
    const errorMessage = data.message ?? data.error_message ?? PROCESSING_MESSAGES.PROCESSING_FAILED_DEFAULT;
    window.alert(`Import failed: ${errorMessage}`);
    return;
  }

  msg.value = data.error_message ?? PROCESSING_MESSAGES.PROCESSING_FAILED_DEFAULT;
}

function handleLogsData(data: LogsPayload): void {
  if (data.logs) {
    workerLog.value = data.after_id != null ? workerLog.value.concat(data.logs) : data.logs;
    lastLogId = data.logs.length > 0 ? data.logs[data.logs.length - 1].id : null;
  }
  loading.logs = false;
}

function handleItemDeleted(): void {
  loading.redirecting = true;
  void router.push('/import');
}

function handleWebSocketError(data: WsErrorPayload): void {
  if (data.code === 404) {
    loading.redirecting = true;
    void router.replace('/import');
  } else if (data.code === 409 && data.file_duplicate?.status === 'duplicate_in_queue') {
    window.alert(data.message ?? 'This upload is a duplicate and cannot be loaded.');
    loading.redirecting = true;
    void router.replace('/import');
  } else {
    console.error('WebSocket error:', data.message);
    msg.value = data.message ?? 'An error occurred';
  }
}

importStatusSocket.on('initial_state', handleInitialState);
importStatusSocket.on('status', handleStatusMessage);
importStatusSocket.on('status_updated', handleStatusUpdate);
importStatusSocket.on('log_added', handleLogAdded);
importStatusSocket.on('item_completed', handleItemCompleted);
importStatusSocket.on('item_failed', handleItemFailed);
importStatusSocket.on('page', (data) => { importData.handlePageData(data); });
importStatusSocket.on('logs', handleLogsData);
importStatusSocket.on('item_deleted', handleItemDeleted);
importStatusSocket.on('error', handleWebSocketError);

function connectWebSocket(): void {
  if (currentId.value != null) {
    importStatusSocket.connect(currentId.value);
  }
}

// ---------------------------------------------------------------------------
// Lifecycle / navigation guards
// ---------------------------------------------------------------------------

function handleBeforeUnload(event: BeforeUnloadEvent): void {
  // Only warn if we're not redirecting due to import completion and there are unsaved changes.
  if (!loading.redirecting && hasUnsavedChanges()) {
    event.preventDefault();
    event.returnValue = '';
  }
}

function clearComponentState(): void {
  importStatusSocket.close();

  currentId.value = null;
  originalFilename.value = null;
  uploadTimestamp.value = null;
  workerLog.value = [];
  lastLogId = null;

  dialogs.mapPreview = false;
  dialogs.featureMap.isOpen = false;
  dialogs.featureMap.selectedIndex = 0;
  dialogs.logs = false;

  loading.logs = true;
  loading.saving = false;
  loading.importing = false;
  loading.redirecting = false;
  loading.recheckingDuplicates = false;

  processing.active = false;
  processing.message = '';
  processing.progress = null;

  fileDuplicate.status = null;
  fileDuplicate.originalFilename = null;

  lockButtons.value = false;
  isImported.value = false;
  importCustomIcons.value = true;

  importData.reset();
  resetBulkOperations();
}

onMounted(async () => {
  window.addEventListener('beforeunload', handleBeforeUnload);
  await fetchUserTags();
});

onBeforeUnmount(() => {
  removeBeforeUnloadHandler();
  clearComponentState();
});

onBeforeRouteLeave(() => {
  if (loading.redirecting) {
    removeBeforeUnloadHandler();
    clearComponentState();
    return true;
  }

  if (hasUnsavedChanges() && !window.confirm('Are you sure you want to leave this page? Your changes may not be saved.')) {
    return false;
  }

  removeBeforeUnloadHandler();
  clearComponentState();
  return true;
});

onBeforeRouteUpdate((to) => {
  const nextId = Array.isArray(to.params.id) ? to.params.id[0] : to.params.id;
  clearComponentState();
  currentId.value = nextId;
  connectWebSocket();
  void loadBulkOperations();
  return true;
});

// Initial connection for this route entry (equivalent of `beforeRouteEnter`).
connectWebSocket();
void loadBulkOperations();

watch(() => route.query.featureHash, (newHash) => {
  if (typeof newHash === 'string' && newHash) {
    void nextTick(() => {
      void waitForPageLoad().then(() => waitForItems()).then(() => {
        scrollToFeatureByHash(newHash);
      });
    });
  }
}, { immediate: true });

watch(() => route.query.scrollToIndex, (rawIndex) => {
  const value = Array.isArray(rawIndex) ? rawIndex[0] : rawIndex;
  if (value == null) return;
  const index = parseInt(value, 10);
  if (!Number.isNaN(index)) {
    void scrollToGlobalIndex(index);
  }
}, { immediate: true });
</script>

<style scoped>
.tag-input {
  text-transform: lowercase;
}

.feature-scroller {
  height: 70vh;
  min-height: 420px;
}
</style>
