import { inject } from 'vue';

export function usePlacesApi() {
  const api = inject('extensionApi');

  async function listPlaces(sort = 'composite') {
    const response = await api.get('/features/', {
      params: { sort },
      headers: { 'Cache-Control': 'no-cache', Pragma: 'no-cache' },
    });
    return response.data?.features ?? [];
  }

  async function getPlace(featureId) {
    const response = await api.get(`/features/${featureId}/`);
    return response.data;
  }

  async function createPlace(payload) {
    const response = await api.post('/features/', payload);
    return response.data;
  }

  async function updatePlace(featureId, payload) {
    const response = await api.put(`/features/${featureId}/`, payload);
    return response.data;
  }

  async function deletePlace(featureId) {
    await api.delete(`/features/${featureId}/`);
  }

  async function recordNavigation(featureId) {
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
