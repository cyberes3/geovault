/**
 * Centralized file type configuration for frontend validation.
 * This should be kept in sync with the backend file_types.py configuration.
 */

// File type configurations
export const FILE_TYPE_CONFIGS = {
  kml: {
    extensions: ['.kml'],
    mimeTypes: [
      'text/xml',
      'application/xml',
      'text/plain',
      'application/octet-stream',
      'application/vnd.google-earth.kml+xml',
      'application/vnd.google-earth.kml'
    ],
    maxSize: 5 * 1024 * 1024, // 5MB
    displayName: 'KML'
  },
  kmz: {
    extensions: ['.kmz'],
    mimeTypes: [
      'application/zip',
      'application/x-zip-compressed',
      'application/octet-stream',
      'application/vnd.google-earth.kmz',
      'application/vnd.google-earth.kmz+xml'
    ],
    maxSize: 200 * 1024 * 1024, // 200MB
    displayName: 'KMZ'
  },
  gpx: {
    extensions: ['.gpx'],
    mimeTypes: [
      'text/xml',
      'application/xml',
      'text/plain',
      'application/octet-stream',
      'application/gpx+xml',
      'application/gpx'
    ],
    maxSize: 5 * 1024 * 1024, // 5MB
    displayName: 'GPX'
  }
}

// Get all supported extensions
export function getAllSupportedExtensions() {
  const extensions = []
  Object.values(FILE_TYPE_CONFIGS).forEach(config => {
    extensions.push(...config.extensions)
  })
  return extensions
}

// Get all supported MIME types
export function getAllSupportedMimeTypes() {
  const mimeTypes = []
  Object.values(FILE_TYPE_CONFIGS).forEach(config => {
    mimeTypes.push(...config.mimeTypes)
  })
  return mimeTypes
}

// Get file type by extension
export function getFileTypeByExtension(filename) {
  const extension = filename.toLowerCase().split('.').pop()
  for (const [type, config] of Object.entries(FILE_TYPE_CONFIGS)) {
    if (config.extensions.includes(`.${extension}`)) {
      return type
    }
  }
  return null
}

// Get file type configuration
export function getFileTypeConfig(fileType) {
  return FILE_TYPE_CONFIGS[fileType]
}

// Validate file extension
export function validateFileExtension(filename) {
  const fileType = getFileTypeByExtension(filename)
  return fileType !== null
}

// Validate file size
export function validateFileSize(file, fileType) {
  const config = getFileTypeConfig(fileType)
  if (!config) return false
  return file.size <= config.maxSize
}

// Validate MIME type
export function validateMimeType(file, fileType) {
  const config = getFileTypeConfig(fileType)
  if (!config) return false
  return !file.type || config.mimeTypes.includes(file.type)
}

// Get human-readable file size
export function formatFileSize(bytes) {
  return (bytes / (1024 * 1024)).toFixed(1) + 'MB'
}

// Get supported file types display string
export function getSupportedFileTypesString() {
  const types = Object.values(FILE_TYPE_CONFIGS).map(config => config.displayName)
  return types.join(', ')
}
