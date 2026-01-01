/**
 * Tag CRUD operations and bulk updates
 */

/**
 * Delete tag and all features with this tag (works for both user and system tags)
 * @param {string} tag - Tag to delete
 * @param {string} csrfToken - CSRF token
 * @returns {Promise<Object>} API response with deleted_count
 */
export async function deleteTag(tag, csrfToken) {
  const response = await fetch('/api/features/bulk-delete-by-tag/', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRFToken': csrfToken || ''
    },
    body: JSON.stringify({ tag })
  });
  
  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.error || `Failed to delete features: ${response.status}`);
  }
  
  return await response.json();
}

// Alias for backwards compatibility (deleteSystemTag is now just deleteTag)
export const deleteSystemTag = deleteTag;

/**
 * Remove tag from all features (bulk operation)
 * @param {string} tag - Tag to remove
 * @param {Array} features - Features with this tag
 * @param {string} csrfToken - CSRF token
 * @returns {Promise<Object>} API response with updated count
 */
export async function removeTagFromAllFeatures(tag, features, csrfToken) {
  const updates = [];
  
  for (const feature of features) {
    const currentTags = Array.isArray(feature.properties.tags)
      ? [...feature.properties.tags]
      : [];
    
    const filteredTags = currentTags.filter(t => t !== tag);
    
    updates.push({
      feature_id: feature.properties.database_id,
      tags: filteredTags
    });
  }
  
  if (updates.length === 0) return { updated: 0 };
  
  const response = await fetch('/api/features/bulk-update-metadata/', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRFToken': csrfToken || ''
    },
    body: JSON.stringify({ updates })
  });
  
  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.error || `Failed to update features: ${response.status}`);
  }
  
  return await response.json();
}

/**
 * Remove tag from a single feature
 * @param {string} tag - Tag to remove
 * @param {Object} feature - Feature object
 * @param {string} csrfToken - CSRF token
 * @returns {Promise<Object>} API response
 */
export async function removeTagFromFeature(tag, feature, csrfToken) {
  const currentTags = Array.isArray(feature.properties.tags)
    ? [...feature.properties.tags]
    : [];
  
  const filteredTags = currentTags.filter(t => t !== tag);
  
  const response = await fetch(`/api/feature/${feature.properties.database_id}/update-metadata/`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRFToken': csrfToken || ''
    },
    body: JSON.stringify({ tags: filteredTags })
  });
  
  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.error || `Failed to update feature: ${response.status}`);
  }
  
  return await response.json();
}

/**
 * Build delete tag confirmation message
 * @param {string} tag - Tag name
 * @param {number} featureCount - Number of features with tag
 * @param {boolean} isSystemTag - Whether this is a system tag (for styling only)
 * @returns {string} Confirmation message
 */
export function buildDeleteTagMessage(tag, featureCount, isSystemTag = false) {
  const tagType = isSystemTag ? 'system tag' : 'tag';
  return `Are you sure you want to delete the ${tagType} "${tag}"?\n\n⚠️ WARNING: This will PERMANENTLY DELETE ${featureCount} ${featureCount === 1 ? 'feature' : 'features'} from your library.\n\nThis action cannot be undone!`;
}

/**
 * Build remove tag from feature confirmation message
 * @param {string} tag - Tag name
 * @param {string} featureName - Feature name
 * @returns {string} Confirmation message
 */
export function buildRemoveTagMessage(tag, featureName) {
  return `Are you sure you want to remove the tag "${tag}" from "${featureName}"?`;
}

/**
 * Scroll to tag element in DOM
 * @param {HTMLElement} containerElement - Container element
 * @param {string} tagName - Tag name to scroll to
 */
export function scrollToTag(containerElement, tagName) {
  if (!containerElement) return;
  
  const tagContainers = containerElement.querySelectorAll('.bg-white.rounded-lg.shadow-sm');
  
  for (const container of tagContainers) {
    const tagHeader = container.querySelector('.bg-gray-50');
    if (tagHeader) {
      const tagSpan = tagHeader.querySelector('span.inline-flex');
      if (tagSpan && tagSpan.textContent.trim() === tagName) {
        container.scrollIntoView({ behavior: 'smooth', block: 'center' });
        break;
      }
    }
  }
}

