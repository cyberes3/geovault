export const APIHOST = "http://127.0.0.1:8000"

// Security configuration
export const SECURITY_CONFIG = {
  // Set to false to disable frontend file validation (for testing backend validation)
  ENABLE_FRONTEND_VALIDATION: false,

  // File size limits (should match backend settings)
  MAX_KML_SIZE: 5 * 1024 * 1024, // 5MB
  MAX_KMZ_SIZE: 10 * 1024 * 1024, // 10MB
}
