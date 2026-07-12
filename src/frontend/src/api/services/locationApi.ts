import { httpClient } from '../httpClient';

export interface UserLocation {
    latitude: number;
    longitude: number;
    city?: string | null;
    state?: string | null;
    country?: string | null;
    [key: string]: unknown;
}

export interface UserLocationResponse {
    location: UserLocation | null;
}

/** GET /api/location/user/ - IP-based geolocation used to recenter the main map on load. */
export async function getUserLocation(): Promise<UserLocation | null> {
    try {
        const response = await httpClient.get<UserLocationResponse>('/api/location/user/');
        return response.data.location ?? null;
    } catch (error) {
        console.error('Error fetching user location:', error);
        return null;
    }
}
