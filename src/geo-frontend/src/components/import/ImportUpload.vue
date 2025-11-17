<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h1 class="text-2xl font-bold text-gray-900 mb-2">Upload Data</h1>
      <p class="text-gray-600">Upload one or more geospatial files (KML, KMZ, GPX) to import data into your feature store.</p>
    </div>

    <!-- File Requirements -->
    <div class="bg-blue-50 border border-blue-200 rounded-lg p-6">
      <div class="flex">
        <div class="flex-shrink-0">
          <svg class="h-5 w-5 text-blue-400" fill="currentColor" viewBox="0 0 20 20">
            <path clip-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" fill-rule="evenodd"></path>
          </svg>
        </div>
        <div class="ml-3">
          <h3 class="text-sm font-medium text-blue-800">Important Guidelines</h3>
          <div class="mt-2 text-sm text-blue-700">
            <ul class="list-disc list-inside space-y-1">
              <li>Only KML/KMZ files are supported</li>
              <li>You can select and upload multiple files at once</li>
              <li>Each file can only be imported once</li>
              <li>Files with the same content or filename are considered duplicates</li>
              <li>Once imported, items cannot be modified</li>
            </ul>
          </div>
        </div>
      </div>
    </div>


    <!-- Upload Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Upload Files</h2>

      <div class="space-y-4">
        <div class="flex justify-end">
          <button
              :disabled="disableUpload || files.length === 0"
              class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:bg-gray-400 disabled:cursor-not-allowed transition-colors duration-200"
              @click="upload"
          >
            <svg class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
            </svg>
            Upload {{ files.length > 0 ? `(${files.length} files)` : '' }}
          </button>
        </div>

        <!-- Progress Bar -->
        <div class="space-y-2">
          <div class="flex justify-between text-sm text-gray-600">
            <span class="flex items-center">
              {{ progressStatusText }}
              <!-- Processing spinner -->
              <svg v-if="isProcessing" class="animate-spin ml-1 mr-2 h-4 w-4 text-blue-600" fill="none" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" fill="currentColor"></path>
              </svg>
            </span>
            <span v-if="overallProgress > 0">{{ overallProgress }}%</span>
          </div>
          <div class="w-full bg-gray-200 rounded-full h-2 relative">
            <div
                :class="progressBarColor"
                :style="{ width: progressBarWidth + '%' }"
                class="h-2 rounded-full transition-all duration-300"
            ></div>
            <!-- Barber pole animation when processing -->
            <div v-if="isProcessing" class="absolute inset-0 h-2 rounded-full overflow-hidden">
              <div class="h-full w-full bg-gradient-to-r from-transparent via-white to-transparent opacity-40 animate-barber-pole"></div>
            </div>
          </div>
        </div>

        <!-- Dropzone -->
        <div
            :class="dropzoneClasses"
            class="border-2 border-dashed border-gray-300 rounded-lg p-6 transition-colors duration-200"
            @dragleave="dragLeave"
            @drop="onDrop"
            @dragover.prevent
            @dragenter.prevent="dragEnter"
        >
          <div v-if="files.length === 0" class="text-center" @dragleave="dragLeave" @dragenter.prevent="dragEnter">
            <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 48 48">
              <path d="M28 8H12a4 4 0 00-4 4v20m32-12v8m0 0v8a4 4 0 01-4 4H12a4 4 0 01-4-4v-4m32-4l-3.172-3.172a4 4 0 00-5.656 0L28 28M8 32l9.172-9.172a4 4 0 015.656 0L28 28m0 0l4 4m4-24h8m-4-4v8m-12 4h.02" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"/>
            </svg>
            <div class="mt-4">
              <p class="text-sm text-gray-600">
                <span class="font-medium text-blue-600 hover:text-blue-500 cursor-pointer" @click="$refs.fileInput.click()">Click to upload</span>
                or drag and drop
              </p>
              <p class="text-xs text-gray-500 mt-1">KML and KMZ files only</p>
            </div>
          </div>

          <div v-else :class="{ 'pointer-events-none opacity-50': disableUpload }" class="space-y-3" @dragleave="dragLeave" @dragenter.prevent="dragEnter">
            <div class="flex items-center justify-between">
              <h3 class="text-sm font-medium text-gray-900">Selected Files ({{ files.length }})</h3>
              <button
                  :disabled="disableUpload"
                  class="text-sm text-red-600 hover:text-red-500 font-medium disabled:text-gray-400 disabled:cursor-not-allowed"
                  @click="clearFiles"
              >
                Clear All
              </button>
            </div>
            <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3 max-h-48 overflow-y-auto p-3 pr-6">
              <div
                  v-for="(file, index) in files"
                  :key="index"
                  class="relative group bg-gray-50 rounded-lg border p-3 hover:bg-gray-100 transition-colors duration-150"
              >
                <!-- Remove button -->
                <button
                    :style="{ top: '-9px' }"
                    :title="`Remove ${file.name}`"
                    class="absolute -right-2 w-6 h-6 bg-red-500 text-white rounded-full flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity duration-150 hover:bg-red-600 shadow-sm z-10"
                    @click="removeFile(index)"
                >
                  <svg class="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path d="M6 18L18 6M6 6l12 12" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                  </svg>
                </button>

                <!-- File icon -->
                <div class="flex justify-center mb-2">
                  <svg class="h-8 w-8 text-blue-500" fill="currentColor" viewBox="0 0 20 20">
                    <path clip-rule="evenodd" d="M4 4a2 2 0 012-2h4.586A2 2 0 0112 2.586L15.414 6A2 2 0 0116 7.414V16a2 2 0 01-2 2H6a2 2 0 01-2-2V4zm2 6a1 1 0 011-1h6a1 1 0 110 2H7a1 1 0 01-1-1zm1 3a1 1 0 100 2h6a1 1 0 100-2H7z" fill-rule="evenodd"></path>
                  </svg>
                </div>

                <!-- File info -->
                <div class="text-center">
                  <p :title="file.name" class="text-xs font-medium text-gray-900 truncate">{{ file.name }}</p>
                  <p class="text-xs text-gray-500 mt-1">{{ formatFileSize(file.size) }}</p>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Hidden file input for click-to-upload -->
        <input
            ref="fileInput"
            accept=".kml,.kmz"
            class="hidden"
            multiple
            type="file"
            @change="onFileChange"
        >

        <!-- Upload Messages -->
        <div v-if="uploadMsg !== ''" :class="messageBoxClass" class="mt-4 p-4 rounded-md">
          <div class="flex">
            <div class="flex-shrink-0">
              <!-- Success icon -->
              <svg v-if="uploadMsg.toLowerCase().includes('success')" :class="messageIconClass" class="h-5 w-5" fill="currentColor" viewBox="0 0 20 20">
                <path clip-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" fill-rule="evenodd"></path>
              </svg>
              <!-- Error/info icon -->
              <svg v-else :class="messageIconClass" class="h-5 w-5" fill="currentColor" viewBox="0 0 20 20">
                <path clip-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" fill-rule="evenodd"></path>
              </svg>
            </div>
            <div class="ml-3 flex-1">
              <p :class="messageTextClass" class="text-sm">{{ uploadMsg }}</p>

            </div>
          </div>
        </div>

        <!-- Detailed Upload Results -->
        <div v-if="uploadResults.successful.length > 0 || uploadResults.failed.length > 0 || uploadResults.skipped.length > 0" class="mt-4 bg-gray-50 border border-gray-200 rounded-md p-6">
          <h4 class="text-sm font-medium text-gray-900 mb-3">Upload Details</h4>
          <div class="h-48 overflow-y-auto space-y-2">

            <!-- Successful uploads -->
            <div v-if="uploadResults.successful.length > 0">
              <h5 class="text-xs font-medium text-green-700 mb-1">✓ Successful ({{ uploadResults.successful.length }})</h5>
              <div class="space-y-1 ml-2">
                <div v-for="result in uploadResults.successful" :key="result.filename" class="text-xs text-green-600">
                  <span class="font-medium">{{ result.filename }}</span>
                  <span class="text-gray-500 ml-1">- {{ result.message }}</span>
                </div>
              </div>
            </div>

            <!-- Skipped uploads -->
            <div v-if="uploadResults.skipped.length > 0">
              <h5 class="text-xs font-medium text-yellow-700 mb-1">⚠ Skipped ({{ uploadResults.skipped.length }})</h5>
              <div class="space-y-1 ml-2">
                <div v-for="result in uploadResults.skipped" :key="result.filename" class="text-xs text-yellow-600">
                  <span class="font-medium">{{ result.filename }}</span>
                  <span class="text-gray-500 ml-1">- {{ result.message }}</span>
                </div>
              </div>
            </div>

            <!-- Failed uploads -->
            <div v-if="uploadResults.failed.length > 0">
              <h5 class="text-xs font-medium text-red-700 mb-1">✗ Failed ({{ uploadResults.failed.length }})</h5>
              <div class="space-y-1 ml-2">
                <div v-for="result in uploadResults.failed" :key="result.filename" class="text-xs text-red-600">
                  <span class="font-medium">{{ result.filename }}</span>
                  <span class="text-gray-500 ml-1">- {{ result.message }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Ready to Import Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-semibold text-gray-900">Ready to Import</h2>
        <!--        <span v-if="loadingQueueList" class="text-sm text-gray-500 italic">Loading...</span>-->
      </div>
      <Importqueue/>
    </div>
  </div>
</template>

<script>
import {mapState} from "vuex"
import {authMixin} from "@/assets/js/authMixin.js";
import axios from "axios";
import {capitalizeFirstLetter} from "@/assets/js/string.js";
import {ImportQueueItem} from "@/assets/js/types/import-types"
import ImportQueue from "@/components/import/parts/ImportQueue.vue";
import {getCookie} from "@/assets/js/auth.js";
import {SECURITY_CONFIG} from "@/config.js";
import {
  getFileTypeByExtension,
  validateFileExtension,
  validateFileSize,
  validateMimeType,
  formatFileSize,
  getSupportedFileTypesString,
  getFileTypeConfig
} from "@/fileTypes.js";

// TODO: after import, don't disable the upload, instead add the new item to a table at the button and then prompt the user to continue

export default {
  computed: {
    ...mapState(["userInfo", "importQueue"]),
    SECURITY_CONFIG() {
      return SECURITY_CONFIG
    },
    progressStatusText() {
      if (this.uploadProgress === 0 && this.files.length === 0) {
        return "Select files to upload"
      } else if (this.uploadProgress === 0 && this.files.length > 0 && this.overallProgress === 0) {
        return `Ready to upload ${this.files.length} file${this.files.length > 1 ? 's' : ''}`
      } else if (this.uploadProgress > 0 && this.uploadProgress < 100) {
        return `Uploading ${this.currentFileIndex + 1}/${this.totalFiles} items...`
      } else if (this.uploadProgress === 100 && this.isProcessing && !this.uploadMsg) {
        return `Uploading ${this.currentFileIndex + 1}/${this.totalFiles} items...`
      } else if (this.uploadProgress === 100 && this.overallProgress < 100 && !this.uploadMsg && !this.isProcessing) {
        return `Uploaded ${this.currentFileIndex + 1}/${this.totalFiles} items...`
      } else if (this.overallProgress === 100 && !this.uploadMsg) {
        return `Upload complete (${this.currentFileIndex + 1}/${this.totalFiles})`
      } else if (this.uploadMsg.toLowerCase().includes("failed") || this.uploadMsg.toLowerCase().includes("error")) {
        return `Upload completed with errors (${this.currentFileIndex + 1}/${this.totalFiles})`
      } else if (this.uploadMsg.toLowerCase().includes("successfully") || this.uploadMsg.toLowerCase().includes("skipped")) {
        return `Upload completed (${this.currentFileIndex + 1}/${this.totalFiles})`
      } else {
        return `Upload complete (${this.currentFileIndex + 1}/${this.totalFiles})`
      }
    },
    progressBarWidth() {
      if (this.overallProgress === 0 && this.files.length === 0) {
        return 0
      } else if (this.overallProgress === 0 && this.files.length > 0) {
        return 0
      } else if (this.overallProgress > 0 && this.overallProgress < 100) {
        return this.overallProgress
      } else if (this.overallProgress === 100) {
        return 100
      } else {
        return 0
      }
    },
    progressBarColor() {
      if (this.isProcessing) {
        return "bg-blue-600"
      } else if (this.overallProgress === 100 && !this.uploadMsg) {
        return "bg-green-600"
      } else if (this.uploadMsg.toLowerCase().includes("failed") || this.uploadMsg.toLowerCase().includes("error")) {
        return "bg-red-600"
      } else if (this.uploadMsg.toLowerCase().includes("skipped") || this.uploadMsg.toLowerCase().includes("already exists")) {
        return "bg-yellow-600"
      } else if (this.overallProgress > 0) {
        return "bg-blue-600"
      } else {
        return "bg-gray-300"
      }
    },
    isSuccessMessage() {
      return (this.overallProgress === 100 && !this.uploadMsg) || this.uploadMsg.toLowerCase().includes("success")
    },
    messageBoxClass() {
      const msg = this.uploadMsg.toLowerCase()
      if (msg.includes("failed") || msg.includes("error") || msg.includes("invalid")) {
        return "bg-red-50 border border-red-200"
      } else if (msg.includes("successfully") || (msg.includes("uploaded") && !msg.includes("failed"))) {
        return "bg-green-50 border border-green-200"
      } else if (msg.includes("skipped") || msg.includes("already exists")) {
        return "bg-yellow-50 border border-yellow-200"
      } else {
        return "bg-gray-50 border border-gray-200"
      }
    },
    messageIconClass() {
      const msg = this.uploadMsg.toLowerCase()
      if (msg.includes("failed") || msg.includes("error") || msg.includes("invalid")) {
        return "text-red-400"
      } else if (msg.includes("successfully") || (msg.includes("uploaded") && !msg.includes("failed"))) {
        return "text-green-400"
      } else if (msg.includes("skipped") || msg.includes("already exists")) {
        return "text-yellow-400"
      } else {
        return "text-gray-400"
      }
    },
    messageTextClass() {
      const msg = this.uploadMsg.toLowerCase()
      if (msg.includes("failed") || msg.includes("error") || msg.includes("invalid")) {
        return "text-red-700"
      } else if (msg.includes("successfully") || (msg.includes("uploaded") && !msg.includes("failed"))) {
        return "text-green-700"
      } else if (msg.includes("skipped") || msg.includes("already exists")) {
        return "text-yellow-700"
      } else {
        return "text-gray-700"
      }
    },
    dropzoneClasses() {
      if (this.isDragOver) {
        return "border-blue-400 bg-blue-50"
      } else if (this.files.length > 0) {
        if (this.disableUpload) {
          return "border-gray-300 bg-gray-50"
        } else {
          return "border-green-300 bg-green-50"
        }
      } else {
        return "border-gray-300 hover:border-gray-400"
      }
    }
  },
  components: {Importqueue: ImportQueue},
  mixins: [authMixin],
  data() {
    return {
      files: [],
      currentFileIndex: 0,
      totalFiles: 0,
      disableUpload: false,
      uploadMsg: "",
      uploadProgress: 0,
      overallProgress: 0,
      loadingQueueList: false,
      refreshInterval: null,
      isDragOver: false,
      isRefreshing: false,
      uploadResults: {
        successful: [],
        failed: [],
        skipped: []
      },
      // Track server processing state
      isProcessing: false,
      processingStartTime: null,
      currentFileUploadComplete: false,
    }
  },
  methods: {
    onFileChange(e) {
      const selectedFiles = Array.from(e.target.files)
      this.addFiles(selectedFiles)
      e.target.value = "" // Reset the input value
    },
    addFiles(selectedFiles) {
      const validFiles = []
      const errors = []

      // Reset progress bar and messages when new files are chosen
      this.uploadProgress = 0
      this.overallProgress = 0
      this.uploadMsg = ""
      this.currentFileIndex = 0
      this.isProcessing = false
      this.processingStartTime = null
      this.currentFileUploadComplete = false

      // Enhanced file validation
      for (const file of selectedFiles) {
        const validationResult = this.validateFile(file)
        if (validationResult.isValid) {
          validFiles.push(file)
        } else {
          errors.push(`${file.name}: ${validationResult.error}`)
        }
      }

      // Show errors if any
      if (errors.length > 0) {
        alert(`File validation errors:\n${errors.join('\n')}`)
        return
      }

      // Add new files to existing ones (don't replace)
      this.files = [...this.files, ...validFiles]
      this.totalFiles = this.files.length
    },
    validateFile(file) {
      // Skip frontend validation if disabled (for testing backend validation)
      if (!SECURITY_CONFIG.ENABLE_FRONTEND_VALIDATION) {
        console.log('Frontend validation disabled - file will be validated by backend only')
        return {isValid: true, error: null}
      }

      // Check for empty files
      if (file.size === 0) {
        return {isValid: false, error: 'File is empty'}
      }

      // Check file extension
      if (!validateFileExtension(file.name)) {
        return {isValid: false, error: `Only ${getSupportedFileTypesString()} files are allowed`}
      }

      // Get file type and validate
      const fileType = getFileTypeByExtension(file.name)
      if (!fileType) {
        return {isValid: false, error: 'Unsupported file type'}
      }

      // Check file size
      if (!validateFileSize(file, fileType)) {
        const config = getFileTypeConfig(fileType)
        return {isValid: false, error: `${config.displayName} file too large. Maximum size: ${formatFileSize(config.maxSize)}`}
      }

      // Check MIME type
      if (!validateMimeType(file, fileType)) {
        return {isValid: false, error: `Invalid MIME type: ${file.type}`}
      }

      // Check filename for suspicious characters
      if (/[<>:"/\\|?*]/.test(file.name)) {
        return {isValid: false, error: 'Filename contains invalid characters'}
      }

      return {isValid: true, error: null}
    },
    onDrop(e) {
      e.preventDefault()
      this.isDragOver = false
      const droppedFiles = Array.from(e.dataTransfer.files)
      this.addFiles(droppedFiles)
    },
    dragEnter(e) {
      e.preventDefault()
      e.stopPropagation()
      this.isDragOver = true
    },
    dragLeave(e) {
      e.preventDefault()
      e.stopPropagation()
      // Only set isDragOver to false if we're leaving the dropzone entirely
      // Check if the related target is outside the dropzone
      const dropzone = e.currentTarget
      if (!dropzone.contains(e.relatedTarget)) {
        this.isDragOver = false
      }
    },
    removeFile(index) {
      this.files.splice(index, 1)
      this.totalFiles = this.files.length
      // Reset progress if no files left
      if (this.files.length === 0) {
        this.uploadProgress = 0
        this.overallProgress = 0
        this.uploadMsg = ""
      }
    },
    clearFiles() {
      this.files = []
      this.totalFiles = 0
      this.uploadProgress = 0
      this.overallProgress = 0
      this.uploadMsg = ""
      this.currentFileIndex = 0
      this.isProcessing = false
      this.processingStartTime = null
      this.currentFileUploadComplete = false
    },
    formatFileSize(bytes) {
      if (bytes === 0) return '0 Bytes'
      const k = 1024
      const sizes = ['Bytes', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(k))
      return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
    },
    async upload() {
      if (this.files.length === 0) {
        return
      }

      this.disableUpload = true
      this.uploadProgress = 0
      this.overallProgress = 0
      this.uploadMsg = ""
      this.currentFileIndex = 0

      // Reset and track results for each file
      this.uploadResults = {
        successful: [],
        failed: [],
        skipped: []
      }

      // Reset processing state
      this.isProcessing = false
      this.processingStartTime = null
      this.currentFileUploadComplete = false

      try {
        for (let i = 0; i < this.files.length; i++) {
          this.currentFileIndex = i
          const file = this.files[i]

          // Calculate overall progress based on completed files
          const baseProgress = (i / this.files.length) * 100

          let formData = new FormData()
          formData.append('file', file)

          // Debug: Log the file being uploaded
          console.log('Uploading file:', file.name, 'Size:', file.size, 'Type:', file.type)

          try {
            // Reset processing state for this file
            this.isProcessing = false
            this.currentFileUploadComplete = false

            const response = await axios.post('/api/data/item/import/upload', formData, {
              headers: {
                'X-CSRFToken': getCookie('csrftoken')
              },
              onUploadProgress: (progressEvent) => {
                // Calculate individual file progress
                const fileProgress = Math.round((progressEvent.loaded * 100) / progressEvent.total)
                // Calculate overall progress: base progress + (current file progress / total files)
                this.overallProgress = Math.round(baseProgress + (fileProgress / this.files.length))
                this.uploadProgress = fileProgress

                // Track when upload completes
                if (fileProgress === 100 && !this.currentFileUploadComplete) {
                  this.currentFileUploadComplete = true
                  this.isProcessing = true
                  this.processingStartTime = Date.now()
                }
              },
            })

            // Server processing complete - clear processing state
            this.isProcessing = false
            this.currentFileUploadComplete = false

            // Calculate processing time if we tracked it
            if (this.processingStartTime) {
              const processingTime = Date.now() - this.processingStartTime
              console.log(`File ${file.name} processed in ${processingTime}ms`)
            }

            // Handle response message
            if (response.data.msg.toLowerCase().includes("success")) {
              this.uploadResults.successful.push({
                filename: file.name,
                message: response.data.msg,
                job_id: response.data.job_id
              })
              // WebSocket will handle real-time updates
            } else {
              // Check if this is a benign error (duplicate file)
              const msg = response.data.msg.toLowerCase()
              if (msg.includes("already") || msg.includes("duplicate")) {
                this.uploadResults.skipped.push({
                  filename: file.name,
                  message: response.data.msg
                })
              } else {
                this.uploadResults.failed.push({
                  filename: file.name,
                  message: response.data.msg
                })
              }
            }
          } catch (fileError) {
            // Clear processing state on error
            this.isProcessing = false
            this.currentFileUploadComplete = false

            // Handle individual file errors without stopping the entire process
            console.error(`Error uploading file ${file.name}:`, fileError)

            let errorMessage = "Upload failed"
            if (fileError.response && fileError.response.data && fileError.response.data.msg) {
              errorMessage = fileError.response.data.msg
            } else if (fileError.response && fileError.response.status === 400) {
              errorMessage = "Invalid file format or upload structure"
            }

            this.uploadResults.failed.push({
              filename: file.name,
              message: errorMessage
            })
          }

          // Don't reset individual file progress to 0 during upload process
          // Keep it at 100 to show completion until all files are done
        }

        // All files processed - show organized results
        this.overallProgress = 100
        this.uploadProgress = 100

        // Generate organized message
        this.uploadMsg = this.generateUploadSummary(this.uploadResults)

        // Clear files and reset input
        this.files = []
        this.totalFiles = 0
        this.currentFileIndex = 0
        this.$refs.fileInput.value = ""

        // Reset progress bar and status message after a short delay to show completion
        setTimeout(() => {
          this.overallProgress = 0
          this.uploadProgress = 0
        }, 1000)

      } catch (error) {
        this.handleError(error)
      }

      this.disableUpload = false
    },
    generateUploadSummary(results) {
      const {successful, failed, skipped} = results
      const total = successful.length + failed.length + skipped.length

      if (total === 0) {
        return "No files were processed"
      }

      let summary = []

      // Add success count
      if (successful.length > 0) {
        summary.push(`${successful.length} file${successful.length > 1 ? 's' : ''} uploaded successfully`)
      }

      // Add skipped count (benign errors)
      if (skipped.length > 0) {
        summary.push(`${skipped.length} file${skipped.length > 1 ? 's' : ''} skipped (already exists)`)
      }

      // Add failed count
      if (failed.length > 0) {
        summary.push(`${failed.length} file${failed.length > 1 ? 's' : ''} failed`)
      }

      return summary.join('. ') + '.'
    },
    handleError(error) {
      console.error("Upload failed:", error)
      console.error("Error response:", error.response)
      console.error("Error response data:", error.response?.data)

      if (error.response && error.response.data && error.response.data.msg != null) {
        this.uploadMsg = error.response.data.msg
      } else if (error.response && error.response.status === 400) {
        this.uploadMsg = "Invalid file format or upload structure. Please check your files and try again."
      } else {
        this.uploadMsg = "Upload failed. Please try again."
      }
    },
    startAutoRefresh() {
      // Clear any existing interval
      this.stopAutoRefresh()

      // Start auto-refresh every 1 second
      this.refreshInterval = setInterval(() => {
        // WebSocket will handle real-time updates
      }, 1000)
    },
    stopAutoRefresh() {
      if (this.refreshInterval) {
        clearInterval(this.refreshInterval)
        this.refreshInterval = null
      }
    },
    handleBeforeUnload(event) {
      // Check if upload is in progress
      if (this.disableUpload) {
        // Modern browsers ignore the custom message and show their own
        event.preventDefault()
        event.returnValue = 'Upload is currently in progress. Are you sure you want to leave this page?'
        return 'Upload is currently in progress. Are you sure you want to leave this page?'
      }
    },
  },
  async created() {
    // Initial fetch of the queue list
    // WebSocket will handle real-time updates
  },
  async mounted() {
    // Start auto-refresh when component is mounted
    this.startAutoRefresh()

    // Add beforeunload event listener to prevent navigation during upload
    window.addEventListener('beforeunload', this.handleBeforeUnload)
  },
  beforeUnmount() {
    // Stop auto-refresh when component is about to be destroyed
    this.stopAutoRefresh()

    // Remove beforeunload event listener
    window.removeEventListener('beforeunload', this.handleBeforeUnload)
  },
  beforeRouteEnter(to, from, next) {
    next(async vm => {
      vm.files = []
      vm.currentFileIndex = 0
      vm.totalFiles = 0
      vm.disableUpload = false
      vm.uploadMsg = ""
      vm.uploadProgress = 0
      vm.overallProgress = 0
      vm.isDragOver = false
      vm.uploadResults = {
        successful: [],
        failed: [],
        skipped: []
      }
      // Start auto-refresh when entering the route
      vm.startAutoRefresh()
    })
  },
  beforeRouteUpdate(to, from, next) {
    // Start auto-refresh when updating to the same route
    this.startAutoRefresh()
    next()
  },
  beforeRouteLeave(to, from, next) {
    // Stop auto-refresh when leaving the route
    this.stopAutoRefresh()

    // Clear upload details when navigating away
    this.uploadResults = {
      successful: [],
      failed: [],
      skipped: []
    }

    // Check if upload is in progress
    if (this.disableUpload) {
      const confirmed = confirm(
          'Upload is currently in progress. Are you sure you want to leave this page? ' +
          'Leaving now may interrupt the upload process.'
      )

      if (!confirmed) {
        // User cancelled, don't navigate away
        // Restart auto-refresh since we're staying on the page
        this.startAutoRefresh()
        return
      }
    }

    // Remove beforeunload event listener before navigating away
    window.removeEventListener('beforeunload', this.handleBeforeUnload)

    next()
  },
  watch: {},
}
</script>

<style scoped>
@keyframes barber-pole {
  0% {
    transform: translateX(-100%);
  }
  100% {
    transform: translateX(100%);
  }
}

.animate-barber-pole {
  animation: barber-pole 1.5s linear infinite;
}
</style>
