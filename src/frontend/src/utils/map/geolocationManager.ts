/**
 * Geolocation Manager
 * Handles browser Geolocation API interactions, tracking states, and permissions.
 */
import type { UserLocation } from '@/api/services/locationApi'

export type GeolocationErrorLike = GeolocationPositionError | Error

export type GeolocationUpdateCallback = (position: UserLocation) => void
export type GeolocationErrorCallback = (error: GeolocationErrorLike) => void

export class GeolocationManager {
  private options: PositionOptions
  private watchId: number | null = null
  private onLocationUpdate: GeolocationUpdateCallback | null = null
  private onError: GeolocationErrorCallback | null = null
  isTracking = false
  currentPosition: UserLocation | null = null

  constructor(options: PositionOptions = {}) {
    this.options = {
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 0,
      ...options
    };
  }

  /** Start tracking the user's location. */
  startTracking(onUpdate: GeolocationUpdateCallback, onError?: GeolocationErrorCallback): void {
    if (!('geolocation' in navigator)) {
      onError?.(new Error('Geolocation is not supported by your browser'));
      return;
    }

    this.onLocationUpdate = onUpdate;
    this.onError = onError ?? null;
    this.isTracking = true;

    this.watchId = navigator.geolocation.watchPosition(
      (position) => {
        this.currentPosition = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracy: position.coords.accuracy,
          heading: position.coords.heading,
          speed: position.coords.speed,
          timestamp: position.timestamp
        };
        this.onLocationUpdate?.(this.currentPosition);
      },
      (error) => {
        this.isTracking = false;
        this.onError?.(error);
      },
      this.options
    );
  }

  /** Stop tracking the user's location. */
  stopTracking(): void {
    if (this.watchId !== null) {
      navigator.geolocation.clearWatch(this.watchId);
      this.watchId = null;
    }
    this.isTracking = false;
    this.currentPosition = null;
  }

  /** Check if location permission is granted. */
  async checkPermission(): Promise<PermissionState | 'unknown'> {
    if (!('permissions' in navigator) || !('query' in navigator.permissions)) {
      return 'unknown';
    }
    try {
      const result = await navigator.permissions.query({ name: 'geolocation' });
      return result.state;
    } catch (error) {
      console.warn('Permissions API query failed:', error);
      return 'unknown';
    }
  }

  /** Get current position once. */
  getCurrentPosition(): Promise<UserLocation> {
    return new Promise((resolve, reject) => {
      if (!('geolocation' in navigator)) {
        reject(new Error('Geolocation is not supported by your browser'));
        return;
      }

      navigator.geolocation.getCurrentPosition(
        (position) => {
          resolve({
            latitude: position.coords.latitude,
            longitude: position.coords.longitude,
            accuracy: position.coords.accuracy,
            timestamp: position.timestamp
          });
        },
        (error) => { reject(error) },
        this.options
      );
    });
  }
}

export const geolocationManager = new GeolocationManager();
