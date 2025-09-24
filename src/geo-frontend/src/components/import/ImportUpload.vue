<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h1 class="text-2xl font-bold text-gray-900 mb-2">Upload Data</h1>
      <p class="text-gray-600">Upload KML/KMZ files to import geospatial data into your feature store.</p>
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
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Upload File</h2>

      <div class="space-y-4">
        <div class="flex items-center space-x-4">
          <div class="flex-1">
            <input
                id="uploadInput"
                :disabled="disableUpload"
                accept=".kml,.kmz"
                class="block w-full text-sm text-gray-500 file:mr-4 file:py-2 file:px-4 file:rounded-md file:border-0 file:text-sm file:font-medium file:bg-blue-50 file:text-blue-700 hover:file:bg-blue-100 disabled:opacity-50 disabled:cursor-not-allowed"
                type="file"
                @change="onFileChange"
            >
          </div>
          <button
              :disabled="disableUpload || !file"
              class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:bg-gray-400 disabled:cursor-not-allowed transition-colors duration-200"
              @click="upload"
          >
            <svg class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
            </svg>
            Upload
          </button>
        </div>

        <!-- Progress Bar -->
        <div class="space-y-2">
          <div class="flex justify-between text-sm text-gray-600">
            <span>{{ progressStatusText }}</span>
            <span v-if="uploadProgress > 0">{{ uploadProgress }}%</span>
          </div>
          <div class="w-full bg-gray-200 rounded-full h-2">
            <div
                :class="progressBarColor"
                :style="{ width: progressBarWidth + '%' }"
                class="h-2 rounded-full transition-all duration-300"
            ></div>
          </div>
        </div>

        <!-- Upload Messages -->
        <div v-if="uploadMsg !== '' && !isSuccessMessage" :class="messageBoxClass" class="mt-4 p-4 rounded-md">
          <div class="flex">
            <div class="flex-shrink-0">
              <svg :class="messageIconClass" class="h-5 w-5" fill="currentColor" viewBox="0 0 20 20">
                <path clip-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" fill-rule="evenodd"></path>
              </svg>
            </div>
            <div class="ml-3">
              <p :class="messageTextClass" class="text-sm">{{ uploadMsg }}</p>
            </div>
          </div>
        </div>
        <div v-else class="w-full" style="height:70px; margin:unset"></div>
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
import {IMPORT_QUEUE_LIST_URL} from "@/assets/js/import/url.js";
import {ImportQueueItem} from "@/assets/js/types/import-types"
import Importqueue from "@/components/import/parts/importqueue.vue";
import {getCookie} from "@/assets/js/auth.js";

// TODO: after import, don't disable the upload, instead add the new item to a table at the button and then prompt the user to continue

export default {
  computed: {
    ...mapState(["userInfo", "importQueue"]),
    progressStatusText() {
      if (this.uploadProgress === 0 && !this.file) {
        return "Select a file to upload"
      } else if (this.uploadProgress === 0 && this.file) {
        return "Ready to upload"
      } else if (this.uploadProgress > 0 && this.uploadProgress < 100) {
        return "Uploading..."
      } else if (this.uploadProgress === 100 && this.uploadMsg.includes("Processing")) {
        return "Processing..."
      } else if (this.uploadProgress === 100 && !this.uploadMsg) {
        return "Upload successful"
      } else if (this.uploadMsg.toLowerCase().includes("error") || this.uploadMsg.toLowerCase().includes("failed")) {
        return "Upload failed"
      } else {
        return "Upload complete"
      }
    },
    progressBarWidth() {
      if (this.uploadProgress === 0 && !this.file) {
        return 0
      } else if (this.uploadProgress === 0 && this.file) {
        return 0
      } else if (this.uploadProgress > 0 && this.uploadProgress < 100) {
        return this.uploadProgress
      } else if (this.uploadProgress === 100) {
        return 100
      } else {
        return 0
      }
    },
    progressBarColor() {
      if (this.uploadProgress === 100 && !this.uploadMsg) {
        return "bg-green-600"
      } else if (this.uploadMsg.toLowerCase().includes("error") || this.uploadMsg.toLowerCase().includes("failed")) {
        return "bg-red-600"
      } else if (this.uploadProgress === 100 && this.uploadMsg && !this.uploadMsg.toLowerCase().includes("processing")) {
        return "bg-yellow-500"
      } else if (this.uploadProgress > 0) {
        return "bg-blue-600"
      } else {
        return "bg-gray-300"
      }
    },
    isSuccessMessage() {
      return (this.uploadProgress === 100 && !this.uploadMsg) || this.uploadMsg.toLowerCase().includes("success")
    },
    messageBoxClass() {
      if (this.uploadMsg.toLowerCase().includes("error") || this.uploadMsg.toLowerCase().includes("failed")) {
        return "bg-red-50 border border-red-200"
      } else {
        return "bg-gray-50 border border-gray-200"
      }
    },
    messageIconClass() {
      if (this.uploadMsg.toLowerCase().includes("error") || this.uploadMsg.toLowerCase().includes("failed")) {
        return "text-red-400"
      } else {
        return "text-gray-400"
      }
    },
    messageTextClass() {
      if (this.uploadMsg.toLowerCase().includes("error") || this.uploadMsg.toLowerCase().includes("failed")) {
        return "text-red-700"
      } else {
        return "text-gray-700"
      }
    }
  },
  components: {Importqueue},
  mixins: [authMixin],
  data() {
    return {
      file: null,
      disableUpload: false,
      uploadMsg: "",
      uploadProgress: 0,
      loadingQueueList: false,
      refreshInterval: null,
    }
  },
  methods: {
    async fetchQueueList() {
      this.loadingQueueList = true
      const response = await axios.get(IMPORT_QUEUE_LIST_URL)
      const ourImportQueue = response.data.data.map((item) => new ImportQueueItem(item))
      this.$store.commit('importQueue', ourImportQueue)
      this.loadingQueueList = false
    },
    onFileChange(e) {
      this.file = e.target.files[0]
      // Reset progress bar and messages when a new file is chosen
      this.uploadProgress = 0
      this.uploadMsg = ""

      const fileType = this.file.name.split('.').pop().toLowerCase()
      if (fileType !== 'kmz' && fileType !== 'kml') {
        alert('Invalid file type. Only KMZ and KML files are allowed.')
        e.target.value = "" // Reset the input value
        this.file = null
      }
    },
    async upload() {
      this.uploadProgress = 0
      this.uploadMsg = ""
      if (this.file == null) {
        return
      }
      let formData = new FormData()
      formData.append('file', this.file)
      try {
        this.disableUpload = true
        const response = await axios.post('/api/data/item/import/upload', formData, {
          headers: {
            'Content-Type': 'multipart/form-data',
            'X-CSRFToken': getCookie('csrftoken')
          },
          onUploadProgress: (progressEvent) => {
            this.uploadProgress = Math.round((progressEvent.loaded * 100) / progressEvent.total)
            if (this.uploadProgress === 100) {
              this.uploadMsg = "Processing..."
            }
          },
        })
        // Don't show success messages in the message box - they're conveyed by the progress bar color
        if (!response.data.msg.toLowerCase().includes("success")) {
          this.uploadMsg = capitalizeFirstLetter(response.data.msg).trim(".") + "."
        } else {
          this.uploadMsg = ""
        }
        await this.fetchQueueList()
        this.file = null
        document.getElementById("uploadInput").value = ""
      } catch (error) {
        this.handleError(error)
      }
      this.disableUpload = false
    },
    handleError(error) {
      console.error("Upload failed:", error)
      if (error.response && error.response.data && error.response.data.msg != null) {
        this.uploadMsg = error.response.data.msg
      } else {
        this.uploadMsg = "Upload failed. Please try again."
      }
    },
    startAutoRefresh() {
      // Clear any existing interval
      this.stopAutoRefresh()

      // Start auto-refresh every 1 second
      this.refreshInterval = setInterval(() => {
        this.fetchQueueList()
      }, 1000)
    },
    stopAutoRefresh() {
      if (this.refreshInterval) {
        clearInterval(this.refreshInterval)
        this.refreshInterval = null
      }
    },
  },
  async created() {
    // Initial fetch of the queue list
    await this.fetchQueueList()
  },
  async mounted() {
    // Start auto-refresh when component is mounted
    this.startAutoRefresh()
  },
  beforeUnmount() {
    // Stop auto-refresh when component is about to be destroyed
    this.stopAutoRefresh()
  },
  beforeRouteEnter(to, from, next) {
    next(async vm => {
      vm.file = null
      vm.disableUpload = false
      vm.uploadMsg = ""
      vm.uploadProgress = 0
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
    next()
  },
  watch: {},
}
</script>

<style scoped>
</style>
