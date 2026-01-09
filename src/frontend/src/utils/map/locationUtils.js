/**
 * User location and forward geocoding utilities for map
 */

/**
 * Fetch user location from API
 * @param {string} apiUrl - Location API URL
 * @returns {Promise<Object|null>} Location object or null
 */
export async function fetchUserLocation(apiUrl) {
  try {
    const response = await fetch(apiUrl);
    const data = await response.json();

    if (response.ok && data.location) {
      console.log('User detected location:', data.location);
      return data.location;
    }
    return null;
  } catch (error) {
    console.error('Error fetching user location:', error);
    return null;
  }
}
