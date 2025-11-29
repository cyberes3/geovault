// Helper to attach geo-data loading and feature bookkeeping methods to GeoJsonMap
import {toLonLat} from 'ol/proj'
import {GeoJSON} from 'ol/format'

export function useGeoData() {
  return {
    async loadDataForCurrentView() {
      // Skip loading if tag filter is active (tag filter manages its own features)
      if (this.isTagFilterActive) {
        return
      }

      // Note: Collection mode now uses bbox loading, so we don't skip it here

      // Cancel any existing request
      if (this.currentAbortController) {
        this.currentAbortController.abort()
      }

      const view = this.map.getView()
      const extent = view.calculateExtent()
      const zoom = view.getZoom()

      // Check if we already loaded data for this area
      const bboxKey = this.getBoundingBoxKey(extent, zoom)

      // Check if this is a world-wide extent by calculating the geographic extent
      const [minX, minY, maxX, maxY] = extent
      const minLonLat = toLonLat([minX, minY])
      const maxLonLat = toLonLat([maxX, maxY])
      const lonSpan = maxLonLat[0] - minLonLat[0]
      const latSpan = maxLonLat[1] - minLonLat[1]

      // Consider it world-wide if longitude span > 300 degrees or latitude span > 150 degrees
      const isWorldWide = lonSpan > 300 || latSpan > 150 || zoom <= 2

      if (isWorldWide) {
        this.loadedBounds.clear()
        // Don't return here - continue to load data
      } else if (this.loadedBounds.has(bboxKey)) {
        // For normal extents, use normal caching
        return
      }

      // Create new AbortController for this request
      this.currentAbortController = new AbortController()
      this.isDataLoading = true
      this.loadError = null // Clear any previous load errors

      try {
        const bboxString = this.getBoundingBoxString(extent)
        const roundedZoom = Math.round(zoom) // Round to integer for API compatibility

        let url, response, data

        if (this.isPublicShareMode) {
          // Prevent API calls if shareId is null or invalid
          if (!this.shareId) {
            return
          }

          // Get share info (cached after first call)
          if (!this.publicShareInfo || this.publicShareInfo.share_id !== this.shareId) {
            const infoUrl = `/api/sharing/public/info/${this.shareId}/`
            const infoResponse = await fetch(infoUrl, {
              signal: this.currentAbortController.signal
            })

            if (!infoResponse.ok) {
              const errorData = await infoResponse.json()
              this.handlePublicShareError(errorData.error || 'Invalid share link')
              return
            }

            const infoData = await infoResponse.json()
            // Response is successful if we got here (infoResponse.ok is true)

            // Cache the share info
            this.publicShareInfo = {
              share_id: this.shareId,
              share_type: infoData.share_type,
              tag: infoData.tag || null,
              collection_name: infoData.collection_name || null,
              collection_id: infoData.collection_id || null,
              include_tags: infoData.include_tags || false,
              allow_downloads: infoData.allow_downloads || false
            }

            // Store tag/collection name for display
            if (infoData.share_type === 'tag') {
              this.publicShareTag = infoData.tag
              this.publicShareCollectionName = null
            } else if (infoData.share_type === 'collection') {
              this.publicShareCollectionName = infoData.collection_name
              this.publicShareTag = null
            }
          }

          // Use appropriate endpoint based on share_type
          if (this.publicShareInfo.share_type === 'tag') {
            url = `${this.SHARE_API_BASE_URL}${this.shareId}/?bbox=${bboxString}&zoom=${roundedZoom}`
          } else if (this.publicShareInfo.share_type === 'collection') {
            url = `/api/sharing/public/collection/${this.shareId}/?bbox=${bboxString}&zoom=${roundedZoom}`
          } else {
            this.publicShareError = 'Unknown share type'
            return
          }

          response = await fetch(url, {
            signal: this.currentAbortController.signal
          })

          data = await response.json()
        } else {
          // Use regular endpoint
          url = this.API_BASE_URL
          // Build URL with optional collection parameter
          url = `${url}?bbox=${bboxString}&zoom=${roundedZoom}`
          if (this.isCollectionMode && this.collectionId) {
            // collectionId is a computed property from route query
            url += `&collection=${this.collectionId}`
          }

          response = await fetch(url, {
            signal: this.currentAbortController.signal
          })
          data = await response.json()
        }

        // Store the tag or collection name for display (from public share response)
        if (this.isPublicShareMode) {
          if (data.tag) {
            this.publicShareTag = data.tag
            this.publicShareCollectionName = null
          } else if (data.collection_name) {
            this.publicShareCollectionName = data.collection_name
            this.publicShareTag = null
          }
        }

        // Check if the response indicates an error
        if (!response.ok) {
          if (this.isPublicShareMode) {
            this.handlePublicShareError(data.error || 'Failed to load shared features.')
          } else {
            this.loadError = data.error || 'Failed to load map data.'
          }
          console.error('Error loading data:', data.error)
          return
        }

        if (response.ok && data.data && data.data.features) {
          // Log error if fallback mechanism was used
          if (data.fallback_used) {
            console.error(
              'ERROR: Spatial query returned suspiciously few results for large extent. ' +
                'Fell back to world-wide query. This may indicate a problem with the spatial query or extent calculation.'
            )
          }

          // Show warning if features were limited by configuration
          if (data.warning) {
            console.warn(data.warning)
          }

          // Use original data without simplification
          const processedData = data.data

          // Add new features to the vector source
          const features = new GeoJSON().readFeatures(processedData, {
            featureProjection: 'EPSG:3857',
            dataProjection: 'EPSG:4326'
          })

          // Manually preserve properties from the original GeoJSON data
          features.forEach((feature, index) => {
            const originalFeature = data.data.features[index]

            if (originalFeature && originalFeature.properties) {
              // Set the properties explicitly
              // Note: Individual properties are accessible via feature.get('properties')
              // Setting them individually is redundant and adds overhead
              feature.set('properties', originalFeature.properties)
            }

            // Set the geojson_hash for efficient duplicate detection
            if (originalFeature && originalFeature.geojson_hash) {
              feature.set('geojson_hash', originalFeature.geojson_hash)
            }
          })

          // Filter out features that already exist in the vector source using hash-based detection
          const existingFeatures = this.vectorSource ? this.vectorSource.getFeatures() : []

          // Create a Set of existing feature hashes for O(1) lookup
          const existingFeatureHashes = new Set()
          existingFeatures.forEach(feature => {
            const hash = feature.get('geojson_hash')
            if (hash) {
              existingFeatureHashes.add(hash)
            }
          })

          // Filter new features using hash-based duplicate detection (O(n) instead of O(n²))
          const newFeatures = features.filter(newFeature => {
            const newHash = newFeature.get('geojson_hash')
            if (!newHash) {
              // If no hash is available, keep the feature (shouldn't happen with backend fix)
              console.warn('Feature missing geojson_hash, keeping feature')
              return true
            }

            // O(1) hash lookup instead of O(n) geometry comparison
            return !existingFeatureHashes.has(newHash)
          })

          if (newFeatures.length > 0) {
            // Add timestamps to new features before adding them to the map
            newFeatures.forEach(feature => {
              this.addFeatureTimestamp(feature)
            })

            if (this.vectorSource) {
              this.vectorSource.addFeatures(newFeatures)
            }

            // Enforce feature limit after adding new features
            this.enforceFeatureLimit()
          }

          this.loadedBounds.add(bboxKey)

          // Batch feature count update to avoid reactivity overhead
          this.scheduleFeatureCountUpdate()
          this.updateLastUpdateTime()

          // Update current zoom
          this.currentZoom = roundedZoom

          // Update features in extent list after loading new features
          this.debouncedUpdateFeaturesInExtent()

          // Mark initial load as complete after first successful load
          if (this.isInitialLoad) {
            this.isInitialLoad = false
          }
        } else {
          console.error('Error loading data:', data.error)
        }
      } catch (error) {
        // Don't log errors for aborted requests
        if (error.name !== 'AbortError') {
          console.error('Error fetching data:', error)
          this.loadError = error.message || 'Failed to load map data. Please try again.'
          // Mark initial load as complete even on error so spinner doesn't stay forever
          if (this.isInitialLoad) {
            this.isInitialLoad = false
          }
        }
      } finally {
        this.isDataLoading = false
        this.currentAbortController = null
      }
    },

    debouncedLoadData() {
      // Cancel any pending request when starting a new debounced request
      if (this.currentAbortController) {
        this.currentAbortController.abort()
      }

      clearTimeout(this.loadTimeout)
      this.loadTimeout = setTimeout(this.loadDataForCurrentView, 500)
    },

    updateFeatureCount() {
      this.featureCount = this.vectorSource ? this.vectorSource.getFeatures().length : 0
    },

    scheduleFeatureCountUpdate() {
      // Batch feature count updates using nextTick to avoid triggering reactivity on every feature
      if (!this.featureCountUpdatePending) {
        this.featureCountUpdatePending = true
        this.$nextTick(() => {
          this.updateFeatureCount()
          this.featureCountUpdatePending = false
        })
      }
    },

    updateLastUpdateTime() {
      this.lastUpdateTime = new Date().toLocaleTimeString()
    },

    enforceFeatureLimit() {
      if (!this.vectorSource) {
        return
      }

      const features = this.vectorSource.getFeatures()
      if (features.length <= this.MAX_FEATURES) {
        return
      }

      // Sort features by timestamp (oldest first) using plain object
      const featuresWithTimestamps = features
        .map(feature => {
          const featureId = this.getFeatureId(feature)
          return {
            feature,
            featureId,
            timestamp: this.featureTimestamps[featureId] || 0
          }
        })
        .sort((a, b) => a.timestamp - b.timestamp)

      // Calculate how many features to remove
      const featuresToRemove = features.length - this.MAX_FEATURES

      // Remove oldest features
      for (let i = 0; i < featuresToRemove; i++) {
        const {feature, featureId} = featuresWithTimestamps[i]
        this.vectorSource.removeFeature(feature)
        delete this.featureTimestamps[featureId]
      }

      this.scheduleFeatureCountUpdate()
      // Update feature list after removing features
      this.debouncedUpdateFeaturesInExtent()
    },

    addFeatureTimestamp(feature) {
      const featureId = this.getFeatureId(feature)
      this.featureTimestamps[featureId] = Date.now()
    },

    clearAllFeatures() {
      // Clear all features and their timestamps
      if (this.vectorSource) {
        this.vectorSource.clear()
      }
      this.featureTimestamps = {}
      this.loadedBounds.clear()
      this.scheduleFeatureCountUpdate()
    }
  }
}


