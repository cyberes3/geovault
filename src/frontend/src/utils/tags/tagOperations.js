/**
 * Tag CRUD operations and bulk updates
 */

/**
 * Delete tag and remove from all features
 * @param {string} tag - Tag to delete
 * @param {Array} features - Features with this tag
 * @param {string} csrfToken - CSRF token
 * @returns {Promise<Object>} API response
 */
export async function deleteTag(tag, features, csrfToken) {
  const updates = [];
  
  for (const feature of features) {
    if (!feature.properties.database_id) continue;
    
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
  if (!feature.properties.database_id) {
    throw new Error('Feature has no database ID');
  }
  
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
 * @returns {string} Confirmation message
 */
export function buildDeleteTagMessage(tag, featureCount) {
  return `Are you sure you want to delete the tag "${tag}"?\n\nThis will remove the tag from ${featureCount} ${featureCount === 1 ? 'feature' : 'features'}.`;
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

