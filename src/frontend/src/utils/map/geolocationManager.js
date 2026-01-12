/**
 * Geolocation Manager
 * Handles browser Geolocation API interactions, tracking states, and permissions.
 */

export class GeolocationManager {
  constructor(options = {}) {
    this.options = {
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 0,
      ...options
    };
    this.watchId = null;
    this.onLocationUpdate = null;
    this.onError = null;
    this.isTracking = false;
    this.currentPosition = null;
  }

  /**
   * Start tracking the user's location
   * @param {Function} onUpdate - Callback for successful location updates
   * @param {Function} onError - Callback for errors
   */
  startTracking(onUpdate, onError) {
    if (!navigator.geolocation) {
      if (onError) onError(new Error('Geolocation is not supported by your browser'));
      return;
    }

    this.onLocationUpdate = onUpdate;
    this.onError = onError;
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
        if (this.onLocationUpdate) {
          this.onLocationUpdate(this.currentPosition);
        }
      },
      (error) => {
        this.isTracking = false;
        if (this.onError) {
          this.onError(error);
        }
      },
      this.options
    );
  }

  /**
   * Stop tracking the user's location
   */
  stopTracking() {
    if (this.watchId !== null) {
      navigator.geolocation.clearWatch(this.watchId);
      this.watchId = null;
    }
    this.isTracking = false;
    this.currentPosition = null;
  }

  /**
   * Check if location permission is granted
   * @returns {Promise<string>} 'granted', 'denied', or 'prompt'
   */
  async checkPermission() {
    if (!navigator.permissions || !navigator.permissions.query) {
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

  /**
   * Get current position once
   * @returns {Promise<Object>} Position object
   */
  getCurrentPosition() {
    return new Promise((resolve, reject) => {
      if (!navigator.geolocation) {
        reject(new Error('Geolocation is not supported by your browser'));
        return;
      }

      navigator.geolocation.getCurrentPosition(
        (position) => {
          const coords = {
            latitude: position.coords.latitude,
            longitude: position.coords.longitude,
            accuracy: position.coords.accuracy,
            timestamp: position.timestamp
          };
          resolve(coords);
        },
        (error) => reject(error),
        this.options
      );
    });
  }
}

export const geolocationManager = new GeolocationManager();
