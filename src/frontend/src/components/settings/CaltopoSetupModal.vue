<template>
  <div
      v-if="show"
      class="fixed inset-0 z-50"
      aria-labelledby="modal-title"
      role="dialog"
      aria-modal="true"
      @click="close"
  >
    <!-- Backdrop -->
    <div class="absolute inset-0 bg-black/50"></div>

    <!-- Modal Container -->
    <div class="absolute inset-0 flex items-stretch justify-stretch sm:items-center sm:justify-center">
      <div
          class="bg-white flex flex-col w-full h-full sm:h-[90vh] sm:max-w-3xl sm:rounded-lg shadow-xl overflow-hidden"
          @click.stop
      >
        <!-- Modal Header (sticky) -->
        <div
            class="sticky top-0 z-10 flex items-center justify-between px-6 py-4 border-b border-gray-200 bg-gray-50 sm:rounded-t-lg">
          <h3 class="text-xl font-semibold text-gray-900" id="modal-title">CalTopo Integration Setup</h3>
          <button
              @click="close"
              class="text-gray-400 hover:text-gray-500 focus:outline-none focus:ring-2 focus:ring-blue-500 rounded-md p-1"
              aria-label="Close modal"
          >
            <XMarkIcon class="w-6 h-6"/>
          </button>
        </div>

        <!-- Modal Content -->
        <div class="flex-1 overflow-y-auto px-6 py-4">
          <div class="prose prose-sm max-w-none space-y-6">
            <!-- Overview Section -->
            <section>
              <div class="space-y-2 text-gray-700">
                <p>To connect GeoVault to your CalTopo account, you'll need to obtain three pieces of information from
                  CalTopo:</p>
                <ul class="list-disc list-inside space-y-1 ml-4">
                  <li><strong>Account ID</strong> - A 6-character identifier for your CalTopo account</li>
                  <li><strong>Credential Code</strong> - A 12-character identifier for the API credential</li>
                  <li><strong>Credential Key</strong> - A 44-character public key for API authentication</li>
                </ul>
                <div class="mt-3 px-3 py-2 bg-blue-50 border border-blue-200 rounded-md">
                  <p>
                    These credentials are specific to CalTopo's API and are different from your CalTopo login
                    credentials. You don't need to share your CalTopo password. These credentials are also different
                    from any external account provider credentials (Google, Yahoo, MSN, Apple, etc.).
                  </p>
                </div>
              </div>
            </section>

            <section>
              <div class="px-3 py-2 bg-red-50 border border-red-200 rounded-md">
                <p class="text-sm text-red-900">
                  <strong>Linking your account here will give GeoVault full write access to your Caltopo
                    account!</strong>
                  GeoVault uses an unofficial, unsupported method to access CalTopo.
                </p>
              </div>
            </section>


            <!-- Step-by-Step Instructions -->
            <section>
              <div class="space-y-4 text-gray-700">
                <div>
                  <h5 class="text-base font-semibold text-gray-900 mb-2">Step 1: Open CalTopo Activation Page</h5>
                  <p class="text-sm mt-2">Make sure you are signed in to your CalTopo account (you should see your
                    username in the top right corner).</p>
                  <p class="text-sm mb-2">In a new browser tab, navigate to:</p>
                  <div class="px-3 py-2 bg-gray-100 border border-gray-300 rounded-md font-mono text-sm break-all">
                    <a href="https://caltopo.com/app/activate/offline?redirect=localhost" target="_blank"
                       rel="noopener noreferrer" class="text-blue-600 hover:text-blue-800 underline">
                      https://caltopo.com/app/activate/offline?redirect=localhost
                    </a>
                  </div>
                </div>

                <div>
                  <h5 class="text-base font-semibold text-gray-900 mb-2">Step 2: Create a New Credential</h5>
                  <p class="text-sm mb-2">On the activation page:</p>
                  <ol class="list-decimal list-inside ml-4 space-y-1 text-sm">
                    <li>Type a name for "Your device will be synced as" (e.g., "GeoVault Integration" or
                      "caltopo_python") - the exact name is not important, but it will help you keep track of
                      credentials if you have several. This name will appear in the Credentials section of your CalTopo
                      account settings.
                    </li>
                    <li>Check the checkbox to agree to the terms</li>
                    <li>Click the "Sync Account" button</li>
                  </ol>
                  <p class="text-sm mt-2">You may see an error page after clicking "Sync Account" - this is normal and
                    expected.</p>
                </div>

                <div>
                  <h5 class="text-base font-semibold text-gray-900 mb-2">Step 3: Find the Activation Code</h5>
                  <p class="text-sm mb-2">You will get redirected to a page like this:</p>
                  <div class="px-3 py-2 bg-gray-100 border border-gray-300 rounded-md font-mono text-sm">
                    https://caltopo.com/app/activate/localhost/client/finish-activate?code=XXXXXXXX&name=GeoVault
                  </div>
                  <p class="text-sm mt-2">The 8-character value after <code
                      class="px-1 py-0.5 bg-gray-200 rounded text-xs">code=</code> from that request URL.</p>
                </div>

                <div>
                  <h5 class="text-base font-semibold text-gray-900 mb-2">Step 4: Get Your Credentials</h5>
                  <p class="text-sm mb-2">In a new browser tab, navigate to:</p>
                  <div class="px-3 py-2 bg-gray-100 border border-gray-300 rounded-md font-mono text-sm break-all">
                    caltopo.com/api/v1/activate?code=<span class="text-blue-600">YOUR_8_CHARACTER_CODE</span>
                  </div>
                  <p class="text-sm mt-2 mb-2">Replace <code class="px-1 py-0.5 bg-gray-200 rounded text-xs">YOUR_8_CHARACTER_CODE</code>
                    with the 8-character code from the previous step.</p>
                  <p class="text-sm mb-2">This should load a page that looks like the following (possibly all compressed
                    into one line):</p>
                  <div
                      class="px-3 py-2 bg-gray-100 border border-gray-300 rounded-md font-mono text-xs overflow-x-auto">
                    <pre class="whitespace-pre-wrap">{{
                        `{
  "code": "XXXXXXXXXXXX",
  "account": {
    "id": "ABC123",
    "type": "Feature",
    "properties": {
      "subscriptionExpires": 1554760038,
      "subscriptionType": "pro-1",
      "subscriptionRenew": true,
      "subscriptionStatus": "active",
      "title": "......@example",
      "class": "UserAccount",
      "updated": 1554760038,
      "email": "......@example.com"
    }
  },
  "key": "xXXXXxXXXXXXXXXxxxXXXXxXxXXXXXXXXXXXX="
}`
                      }}</pre>
                  </div>
                </div>

                <div>
                  <h5 class="text-base font-semibold text-gray-900 mb-2">Step 5: Extract Your Credentials</h5>
                  <p class="text-sm mb-2">From the JSON response, copy these three values:</p>
                  <ul class="list-disc list-inside ml-4 space-y-2 text-sm">
                    <li>
                      <strong>Account ID:</strong> The 6-character value from <code
                        class="px-1 py-0.5 bg-gray-200 rounded text-xs">account.id</code> (e.g., "ABC123")
                    </li>
                    <li>
                      <strong>Credential Code:</strong> The 12-character value from <code
                        class="px-1 py-0.5 bg-gray-200 rounded text-xs">code</code> (e.g., "XXXXXXXXXXXX")
                    </li>
                    <li>
                      <strong>Credential Key:</strong> The 44-character value from <code
                        class="px-1 py-0.5 bg-gray-200 rounded text-xs">key</code> (e.g.,
                      "xXXXXxXXXXXXXXXxxxXXXXxXxXXXXXXXXXXXX=")
                    </li>
                  </ul>
                </div>

                <div>
                  <h5 class="text-base font-semibold text-gray-900 mb-2">Step 6: Enter Credentials in GeoVault</h5>
                  <p class="text-sm">Return to this page and enter the three values you copied into the connection form
                    above. Click "Connect to CalTopo" to complete the setup.</p>
                </div>
              </div>
            </section>

            <!-- Troubleshooting Section -->
            <section>
              <h4 class="text-lg font-semibold text-gray-900 mb-3">Troubleshooting</h4>
              <div class="space-y-3 text-gray-700">
                <div>
                  <h5 class="text-base font-semibold text-gray-900 mb-1">Can't find the finish-activate request?</h5>
                  <p class="text-sm">Make sure network monitoring is active (red indicator in Network tab) before
                    clicking "Sync Account". Try refreshing the activation page and starting over.</p>
                </div>
                <div>
                  <h5 class="text-base font-semibold text-gray-900 mb-1">The activation API returns an error?</h5>
                  <p class="text-sm">Make sure you're using the 8-character code from the finish-activate request, not
                    the credential code. The code expires quickly, so use it immediately after copying.</p>
                </div>
                <div>
                  <h5 class="text-base font-semibold text-gray-900 mb-1">Connection fails after entering
                    credentials?</h5>
                  <p class="text-sm">Double-check that you copied all three values correctly, including the full
                    44-character key. Make sure there are no extra spaces or line breaks.</p>
                </div>
              </div>
            </section>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {XMarkIcon} from '@heroicons/vue/24/outline'

export default {
  name: 'CaltopoSetupModal',
  components: {
    XMarkIcon
  },
  props: {
    show: {
      type: Boolean,
      required: true,
      default: false
    }
  },
  methods: {
    close() {
      this.$emit('close')
    },
    handleEscapeKey(event) {
      if (event.key === 'Escape' && this.show) {
        this.close()
      }
    }
  },
  mounted() {
    // Add keyboard event listener for Escape key to close modal
    document.addEventListener('keydown', this.handleEscapeKey)
  },
  beforeUnmount() {
    // Remove keyboard event listener
    document.removeEventListener('keydown', this.handleEscapeKey)
  },
  watch: {
    show(newVal) {
      // Prevent body scroll when modal is open
      if (newVal) {
        document.body.style.overflow = 'hidden'
      } else {
        document.body.style.overflow = ''
      }
    }
  }
}
</script>

<style scoped>
kbd {
  font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace;
}
</style>

