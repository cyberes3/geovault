import { inject } from 'vue';
import type { ExtensionApi } from '@/types/extension-api';
import type { PlaceFeature } from '@/types/places';

export interface UsePlacesApiReturn {
  api: ExtensionApi;
  listPlaces: (sort?: string) => Promise<PlaceFeature[]>;
  getPlace: (featureId: number) => Promise<PlaceFeature>;
  createPlace: (payload: unknown) => Promise<PlaceFeature>;
  updatePlace: (featureId: number, payload: unknown) => Promise<PlaceFeature>;
  deletePlace: (featureId: number) => Promise<void>;
  recordNavigation: (featureId: number) => Promise<void>;
}

export function usePlacesApi(): UsePlacesApiReturn {
  const api = inject('extensionApi') as ExtensionApi;

  async function listPlaces(sort = 'composite'): Promise<PlaceFeature[]> {
    const response = await api.get('/features/', {
      params: { sort },
      headers: { 'Cache-Control': 'no-cache', Pragma: 'no-cache' },
    });
    const data = response.data as { features?: PlaceFeature[] } | null;
    return data?.features ?? [];
  }

  async function getPlace(featureId: number): Promise<PlaceFeature> {
    const response = await api.get(`/features/${featureId}/`);
    return response.data as PlaceFeature;
  }

  async function createPlace(payload: unknown): Promise<PlaceFeature> {
    const response = await api.post('/features/', payload);
    return response.data as PlaceFeature;
  }

  async function updatePlace(featureId: number, payload: unknown): Promise<PlaceFeature> {
    const response = await api.put(`/features/${featureId}/`, payload);
    return response.data as PlaceFeature;
  }

  async function deletePlace(featureId: number): Promise<void> {
    await api.delete(`/features/${featureId}/`);
  }

  async function recordNavigation(featureId: number): Promise<void> {
    await api.post(`/features/${featureId}/navigate/`);
  }

  return {
    api,
    listPlaces,
    getPlace,
    createPlace,
    updatePlace,
    deletePlace,
    recordNavigation,
  };
}
