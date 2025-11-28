import { fetchConfig } from './configService'

// System tag prefixes that identify automatically generated tags.
// Fetched from backend config on initialization
let SYSTEM_TAG_PREFIXES = []

// Initialize system tags from config
// This is called once when the module loads
let initPromise = null

/**
 * Initialize system tag prefixes from server config
 * @returns {Promise<void>}
 */
async function initializeSystemTags() {
  if (initPromise) {
    return initPromise
  }
  
  initPromise = fetchConfig()
    .then(config => {
      SYSTEM_TAG_PREFIXES = config.systemTagPrefixes || []
    })
    .catch(error => {
      console.error('Error initializing system tags from config:', error)
      // Fallback to empty array if config fetch fails
      SYSTEM_TAG_PREFIXES = []
    })
    .finally(() => {
      initPromise = null
    })
  
  return initPromise
}

// Start initialization immediately
initializeSystemTags()

/**
 * Check if a tag is a system tag (protected tag).
 * Matches the backend's is_protected_tag logic.
 * 
 * @param {string} tag - The tag to check
 * @returns {boolean} True if the tag is a system tag, False otherwise
 */
export function isSystemTag(tag) {
  if (!tag || typeof tag !== 'string') {
    return false
  }
  
  // If system tags haven't been loaded yet, return false
  // This is a defensive check - in practice, config should load quickly
  if (SYSTEM_TAG_PREFIXES.length === 0) {
    // Try to initialize if not already in progress
    if (!initPromise) {
      initializeSystemTags()
    }
    return false
  }
  
  const lowerTag = tag.toLowerCase()
  
  for (const prefix of SYSTEM_TAG_PREFIXES) {
    // Exact match
    if (lowerTag === prefix) {
      return true
    }
    // Prefix match (e.g., "type:point" matches "type")
    if (lowerTag.startsWith(prefix + ':')) {
      return true
    }
  }
  
  return false
}

/**
 * Get the current system tag prefixes (for debugging or other uses)
 * @returns {string[]} Array of system tag prefixes
 */
export function getSystemTagPrefixes() {
  return [...SYSTEM_TAG_PREFIXES]
}

/**
 * Ensure system tags are initialized (useful for components that need to wait)
 * @returns {Promise<void>}
 */
export function ensureSystemTagsInitialized() {
  return initializeSystemTags()
}

/**
 * Filter out system tags from a list of tags.
 * 
 * @param {string[]} tags - List of tags to filter
 * @returns {string[]} List of tags with system tags removed
 */
export function filterSystemTags(tags) {
  if (!Array.isArray(tags)) {
    return []
  }
  
  return tags.filter(tag => !isSystemTag(tag))
}

