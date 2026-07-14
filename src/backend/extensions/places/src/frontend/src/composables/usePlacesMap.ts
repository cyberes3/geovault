import { shallowRef, type ShallowRef } from 'vue';
import { createPlacesMap, type FitOptions, type PlacesMapController } from '@/utils/placesMaplibre';
import { ensureUserSettingsLoaded, getDefaultMapSourceId } from '@/utils/placesMapSettings';
import type { MaplibreMap } from '@/types/maplibre';
import type { PlaceFeature, PlaceMapFeature, PlaceMapFeatureProperties } from '@/types/places';

const INITIAL_CENTER: [number, number] = [0, 0];
const INITIAL_ZOOM = 2;
const FOCUS_ZOOM = 12;
const MAP_FEATURE_FIT_OPTIONS: FitOptions = {
  focusZoom: FOCUS_ZOOM,
  fitPadding: { top: 100, right: 100, bottom: 140, left: 140 },
  fitMaxZoom: 15,
};

export interface UsePlacesMapOptions {
  sourceId: string;
  layerId: string;
  mode?: 'list' | 'edit';
}

interface UpdateMapFeaturesOptions {
  fit?: boolean;
}

export interface UsePlacesMapReturn {
  map: ShallowRef<MaplibreMap | null>;
  mapController: ShallowRef<PlacesMapController | null>;
  programmaticMapMove: ShallowRef<boolean>;
  MAP_FEATURE_FIT_OPTIONS: FitOptions;
  getMapFeatureCollection: (places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null) => { type: 'FeatureCollection'; features: PlaceMapFeature[] };
  updateMapFeatures: (places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null, options?: UpdateMapFeaturesOptions) => void;
  initMap: (container: HTMLElement | null | undefined, places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null) => Promise<PlacesMapController | null>;
  applyDefaultBasemapFromUserSettings: (places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null) => Promise<string | null>;
  setBaseSource: (sourceId: string, places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null) => Promise<string>;
  resetMapToDefaultExtent: (places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null) => void;
  focusPlace: (place: PlaceFeature | null, zoom?: boolean) => void;
  destroyMap: () => void;
}

export function usePlacesMap({
  sourceId,
  layerId,
  mode = 'list',
}: UsePlacesMapOptions): UsePlacesMapReturn {
  const map: ShallowRef<MaplibreMap | null> = shallowRef(null);
  const mapController: ShallowRef<PlacesMapController | null> = shallowRef(null);
  const programmaticMapMove = shallowRef(false);

  function getMapFeatureCollection(places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null): { type: 'FeatureCollection'; features: PlaceMapFeature[] } {
    return {
      type: 'FeatureCollection',
      features: places
        .filter((place) => place.geometry.coordinates.length >= 2)
        .map((place) => {
          const placeId = place.properties.database_id;
          const isSelected = selectedPlaceId === placeId;
          const isHovered = hoveredPlaceId != null && placeId === hoveredPlaceId;
          const properties: PlaceMapFeatureProperties = {
            database_id: placeId,
            is_highlighted: isSelected || isHovered ? 1 : 0,
          };
          return {
            type: 'Feature',
            geometry: {
              type: 'Point',
              coordinates: [place.geometry.coordinates[0], place.geometry.coordinates[1]],
            },
            properties,
          };
        }),
    };
  }

  function updateMapFeatures(places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null, options: UpdateMapFeaturesOptions = { fit: false }): void {
    if (!mapController.value) {
      return;
    }
    const data = getMapFeatureCollection(places, selectedPlaceId, hoveredPlaceId);
    mapController.value.setPointFeatures(data.features);
    if (options.fit) {
      mapController.value.fitToPointFeatures(data.features, MAP_FEATURE_FIT_OPTIONS);
    }
  }

  async function initMap(container: HTMLElement | null | undefined, places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null): Promise<PlacesMapController | null> {
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

  async function applyDefaultBasemapFromUserSettings(places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null): Promise<string | null> {
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

  async function setBaseSource(sourceId: string, places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null): Promise<string> {
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

  function resetMapToDefaultExtent(places: PlaceFeature[], selectedPlaceId: number | null, hoveredPlaceId: number | null): void {
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

  function focusPlace(place: PlaceFeature | null, zoom = true): void {
    if (!map.value || !place || place.geometry.coordinates.length < 2 || !zoom) {
      return;
    }
    programmaticMapMove.value = true;
    map.value.easeTo({
      center: [place.geometry.coordinates[0], place.geometry.coordinates[1]],
      zoom: FOCUS_ZOOM,
      duration: 0,
    });
  }

  function destroyMap(): void {
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
