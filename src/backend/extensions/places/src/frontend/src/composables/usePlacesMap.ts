import { shallowRef } from 'vue';
import { createPlacesMap } from '@/utils/placesMaplibre.js';
import { ensureUserSettingsLoaded, getDefaultMapSourceId } from '@/utils/placesMapSettings.js';

const INITIAL_CENTER = [0, 0];
const INITIAL_ZOOM = 2;
const FOCUS_ZOOM = 12;
const MAP_FEATURE_FIT_OPTIONS = {
  focusZoom: FOCUS_ZOOM,
  fitPadding: { top: 100, right: 100, bottom: 140, left: 140 },
  fitMaxZoom: 15,
};

export function usePlacesMap({
  sourceId,
  layerId,
  mode = 'list',
}) {
  const map = shallowRef(null);
  const mapController = shallowRef(null);
  const programmaticMapMove = shallowRef(false);

  function getMapFeatureCollection(places, selectedPlaceId, hoveredPlaceId) {
    return {
      type: 'FeatureCollection',
      features: places
        .filter((place) => Array.isArray(place?.geometry?.coordinates) && place.geometry.coordinates.length >= 2)
        .map((place) => {
          const placeId = place.properties.database_id;
          const isSelected = selectedPlaceId === placeId;
          const isHovered = hoveredPlaceId != null && placeId === hoveredPlaceId;
          return {
            type: 'Feature',
            geometry: {
              type: 'Point',
              coordinates: [place.geometry.coordinates[0], place.geometry.coordinates[1]],
            },
            properties: {
              database_id: placeId,
              is_highlighted: isSelected || isHovered ? 1 : 0,
            },
          };
        }),
    };
  }

  function updateMapFeatures(places, selectedPlaceId, hoveredPlaceId, options = { fit: false }) {
    if (!mapController.value) {
      return;
    }
    const data = getMapFeatureCollection(places, selectedPlaceId, hoveredPlaceId);
    mapController.value.setPointFeatures(data.features);
    if (options.fit) {
      mapController.value.fitToPointFeatures(data.features, MAP_FEATURE_FIT_OPTIONS);
    }
  }

  async function initMap(container, places, selectedPlaceId, hoveredPlaceId) {
    if (mapController.value) {
      mapController.value.destroy();
      mapController.value = null;
      map.value = null;
    }
    if (!container) {
      return null;
    }

    await ensureUserSettingsLoaded();
    const initialFeatures = getMapFeatureCollection(places, selectedPlaceId, hoveredPlaceId).features;
    const controller = await createPlacesMap({
      container,
      mode,
      sourceId,
      layerId,
      preferredSourceId: getDefaultMapSourceId(),
      minZoom: 1,
      maxZoom: 18,
      initialPointFeatures: initialFeatures,
      initialFitOptions: MAP_FEATURE_FIT_OPTIONS,
    });

    mapController.value = controller;
    map.value = controller.map;
    return controller;
  }

  async function applyDefaultBasemapFromUserSettings(places, selectedPlaceId, hoveredPlaceId) {
    if (!mapController.value) {
      return null;
    }
    const desired = getDefaultMapSourceId();
    try {
      const nextId = await mapController.value.setBaseSource(desired);
      updateMapFeatures(places, selectedPlaceId, hoveredPlaceId);
      return nextId;
    } catch {
      return mapController.value.getCurrentBaseSourceId();
    }
  }

  async function setBaseSource(sourceId, places, selectedPlaceId, hoveredPlaceId) {
    if (!mapController.value) {
      return sourceId;
    }
    try {
      const nextId = await mapController.value.setBaseSource(sourceId);
      updateMapFeatures(places, selectedPlaceId, hoveredPlaceId);
      return nextId;
    } catch {
      return mapController.value.getCurrentBaseSourceId();
    }
  }

  function resetMapToDefaultExtent(places, selectedPlaceId, hoveredPlaceId) {
    if (!map.value || !mapController.value) {
      return;
    }
    const features = getMapFeatureCollection(places, selectedPlaceId, hoveredPlaceId).features;
    if (features.length > 0) {
      mapController.value.fitToPointFeatures(features, MAP_FEATURE_FIT_OPTIONS);
      map.value.setBearing(0);
    } else {
      map.value.easeTo({
        center: INITIAL_CENTER,
        zoom: INITIAL_ZOOM,
        bearing: 0,
        duration: 0,
      });
    }
    updateMapFeatures(places, selectedPlaceId, hoveredPlaceId);
  }

  function focusPlace(place, zoom = true) {
    if (!map.value || !place?.geometry?.coordinates || !zoom) {
      return;
    }
    programmaticMapMove.value = true;
    map.value.easeTo({
      center: [place.geometry.coordinates[0], place.geometry.coordinates[1]],
      zoom: FOCUS_ZOOM,
      duration: 0,
    });
  }

  function destroyMap() {
    if (mapController.value) {
      mapController.value.destroy();
      mapController.value = null;
      map.value = null;
    }
  }

  return {
    map,
    mapController,
    programmaticMapMove,
    MAP_FEATURE_FIT_OPTIONS,
    getMapFeatureCollection,
    updateMapFeatures,
    initMap,
    applyDefaultBasemapFromUserSettings,
    setBaseSource,
    resetMapToDefaultExtent,
    focusPlace,
    destroyMap,
  };
}
