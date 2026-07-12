/**
 * Universal toggle utility functions for managing array/Set selections.
 *
 * These functions can be used with ToggleButton components to handle
 * adding/removing items from arrays or Sets based on boolean toggle values.
 */

interface ToggleArrayItemOptions {
  /** If true, handles string/number conversion (e.g. for feature IDs). */
  normalizeTypes?: boolean;
}

/** Toggle an item in an array based on a boolean value. Returns true if the item ended up added. */
export function toggleArrayItem<T>(array: T[], item: T, value: boolean, options: ToggleArrayItemOptions = {}): boolean {
  if (!Array.isArray(array)) {
    console.warn('toggleArrayItem: array parameter must be an Array');
    return false;
  }

  const { normalizeTypes = false } = options;

  if (normalizeTypes) {
    // Handle string/number conversion for cases like feature IDs
    const itemStr = String(item) as unknown as T;
    const itemNum = Number(item) as unknown as T;

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

/** Toggle an item in a Set based on a boolean value. Returns true if the item ended up added. */
export function toggleSetItem<T>(set: Set<T>, item: T, value: boolean): boolean {
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

/** Universal toggle function that automatically detects array vs Set. */
export function toggleItem<T>(collection: T[] | Set<T>, item: T, value: boolean, options: ToggleArrayItemOptions = {}): boolean {
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
 * Create a bound toggle function for use in Vue templates. This is a convenience function that
 * returns a function bound to a specific collection.
 *
 * @example
 * // In Vue component:
 * const handleToggle = createToggleHandler(this.formData.tags);
 * // In template:
 * @update:model-value="(value) => handleToggle(tag, value)"
 */
export function createToggleHandler<T>(collection: T[] | Set<T>, options: ToggleArrayItemOptions = {}): (item: T, value: boolean) => boolean {
  return (item: T, value: boolean) => toggleItem(collection, item, value, options);
}
