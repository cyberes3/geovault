/**
 * Universal toggle utility functions for managing array/Set selections
 * 
 * These functions can be used with ToggleButton components to handle
 * adding/removing items from arrays or Sets based on boolean toggle values.
 */

/**
 * Toggle an item in an array based on a boolean value
 * 
 * @param {Array} array - The array to modify
 * @param {*} item - The item to add or remove
 * @param {boolean} value - true to add, false to remove
 * @param {Object} options - Optional configuration
 * @param {boolean} options.normalizeTypes - If true, handles string/number conversion (default: false)
 * @returns {boolean} - true if item was added, false if removed
 */
export function toggleArrayItem(array, item, value, options = {}) {
  if (!Array.isArray(array)) {
    console.warn('toggleArrayItem: array parameter must be an Array');
    return false;
  }

  const { normalizeTypes = false } = options;

  if (normalizeTypes) {
    // Handle string/number conversion for cases like feature IDs
    const itemStr = String(item);
    const itemNum = Number(item);
    
    if (value) {
      // Check if already in array (as string or number)
      const exists = array.includes(itemStr) || array.includes(itemNum);
      if (!exists) {
        // Store as string to match typical form behavior
        array.push(itemStr);
        return true;
      }
    } else {
      // Remove from array (check both string and number forms)
      const strIndex = array.indexOf(itemStr);
      if (strIndex > -1) {
        array.splice(strIndex, 1);
        return false;
      }
      const numIndex = array.indexOf(itemNum);
      if (numIndex > -1) {
        array.splice(numIndex, 1);
        return false;
      }
    }
  } else {
    // Simple add/remove without type conversion
    if (value) {
      if (!array.includes(item)) {
        array.push(item);
        return true;
      }
    } else {
      const index = array.indexOf(item);
      if (index > -1) {
        array.splice(index, 1);
        return false;
      }
    }
  }

  return value;
}

/**
 * Toggle an item in a Set based on a boolean value
 * 
 * @param {Set} set - The Set to modify
 * @param {*} item - The item to add or remove
 * @param {boolean} value - true to add, false to remove
 * @returns {boolean} - true if item was added, false if removed
 */
export function toggleSetItem(set, item, value) {
  if (!(set instanceof Set)) {
    console.warn('toggleSetItem: set parameter must be a Set');
    return false;
  }

  if (value) {
    set.add(item);
    return true;
  } else {
    set.delete(item);
    return false;
  }
}

/**
 * Universal toggle function that automatically detects array vs Set
 * 
 * @param {Array|Set} collection - The array or Set to modify
 * @param {*} item - The item to add or remove
 * @param {boolean} value - true to add, false to remove
 * @param {Object} options - Optional configuration
 * @param {boolean} options.normalizeTypes - If true, handles string/number conversion for arrays (default: false)
 * @returns {boolean} - true if item was added, false if removed
 */
export function toggleItem(collection, item, value, options = {}) {
  if (Array.isArray(collection)) {
    return toggleArrayItem(collection, item, value, options);
  } else if (collection instanceof Set) {
    return toggleSetItem(collection, item, value);
  } else {
    console.warn('toggleItem: collection must be an Array or Set');
    return false;
  }
}

/**
 * Create a bound toggle function for use in Vue templates
 * This is a convenience function that returns a function bound to a specific collection
 * 
 * @param {Array|Set} collection - The array or Set to modify
 * @param {Object} options - Optional configuration
 * @param {boolean} options.normalizeTypes - If true, handles string/number conversion for arrays (default: false)
 * @returns {Function} - A function that can be called with (item, value) to toggle
 * 
 * @example
 * // In Vue component:
 * const handleToggle = createToggleHandler(this.formData.tags);
 * // In template:
 * @update:model-value="(value) => handleToggle(tag, value)"
 */
export function createToggleHandler(collection, options = {}) {
  return (item, value) => {
    return toggleItem(collection, item, value, options);
  };
}

