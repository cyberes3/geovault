/**
 * Centralized file type configuration for frontend validation.
 * This should be kept in sync with the backend file_types.py configuration.
 */

export interface FileTypeConfig {
  extensions: string[]
  mimeTypes: string[]
  maxSize: number
  displayName: string
}

export type FileTypeKey = 'kml' | 'kmz' | 'gpx'

export const FILE_TYPE_CONFIGS: Record<FileTypeKey, FileTypeConfig> = {
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
    maxSize: 5 * 1024 * 1024, // 5MB
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

/** Get all supported extensions */
export function getAllSupportedExtensions(): string[] {
  const extensions: string[] = []
  Object.values(FILE_TYPE_CONFIGS).forEach(config => {
    extensions.push(...config.extensions)
  })
  return extensions
}

/** Get all supported MIME types */
export function getAllSupportedMimeTypes(): string[] {
  const mimeTypes: string[] = []
  Object.values(FILE_TYPE_CONFIGS).forEach(config => {
    mimeTypes.push(...config.mimeTypes)
  })
  return mimeTypes
}

/** Get file type by extension */
export function getFileTypeByExtension(filename: string): FileTypeKey | null {
  const extension = filename.toLowerCase().split('.').pop()
  for (const [type, config] of Object.entries(FILE_TYPE_CONFIGS)) {
    if (config.extensions.includes(`.${extension}`)) {
      return type as FileTypeKey
    }
  }
  return null
}

/** Get file type configuration */
export function getFileTypeConfig(fileType: FileTypeKey | null | undefined): FileTypeConfig | undefined {
  if (!fileType) return undefined
  return FILE_TYPE_CONFIGS[fileType]
}

/** Validate file extension */
export function validateFileExtension(filename: string): boolean {
  const fileType = getFileTypeByExtension(filename)
  return fileType !== null
}

/** Validate file size */
export function validateFileSize(file: File, fileType: FileTypeKey | null | undefined): boolean {
  const config = getFileTypeConfig(fileType)
  if (!config) return false
  return file.size <= config.maxSize
}

/** Validate MIME type */
export function validateMimeType(file: File, fileType: FileTypeKey | null | undefined): boolean {
  const config = getFileTypeConfig(fileType)
  if (!config) return false
  return !file.type || config.mimeTypes.includes(file.type)
}

/** Get human-readable file size */
export function formatFileSize(bytes: number): string {
  return (bytes / (1024 * 1024)).toFixed(1) + 'MB'
}

/** Get supported file types display string */
export function getSupportedFileTypesString(): string {
  const types = Object.values(FILE_TYPE_CONFIGS).map(config => config.displayName)
  return types.join(', ')
}
