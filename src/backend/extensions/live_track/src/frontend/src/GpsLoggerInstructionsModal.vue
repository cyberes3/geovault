<template>
  <BaseModal :is-open="true" title="Configure GPSLogger" :on-top="true" @close="$emit('close')">
    <div class="p-4 space-y-6 text-sm">
      <p class="text-gray-900">
        In the GPSLogger app, follow these steps to send your location to this track.
      </p>
      <div class="space-y-4">
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">1</span>
          <div>
            <p class="font-medium text-gray-900">Enable Log GPS/GNSS locations and Log network locations</p>
            <p class="text-gray-900">In GPSLogger go to <strong>Settings → Performance</strong>. Turn on <strong>Log GPS/GNSS locations</strong> and <strong>Log network locations</strong> so the app records positions from satellites and from the network.</p>
          </div>
        </div>
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">2</span>
          <div>
            <p class="font-medium text-gray-900">Movement-based logging</p>
            <p class="text-gray-900 mb-2">In <strong>Settings → Performance</strong> you can limit how often points are recorded:</p>
            <ul class="list-disc list-inside text-gray-900 space-y-1 mb-2">
              <li><strong>Logging interval</strong> – seconds between points. Suggested: <strong>30</strong> for walking, <strong>15</strong> for biking, <strong>10</strong> for driving. Use 0 for maximum frequency (more battery use).</li>
              <li><strong>Distance filter</strong> – minimum meters between points. Suggested: <strong>10</strong> for walking, <strong>30</strong> for biking, <strong>100</strong> for driving. Use 0 to log as often as the interval allows.</li>
              <li><strong>Only log if significant motion</strong> – turn on to log only when the device detects movement; saves battery when stationary.</li>
            </ul>
            <p class="text-gray-900 text-xs">Interval and distance work together: a point is logged at most every N seconds and only after you've moved the set distance (whichever is later).</p>
          </div>
        </div>
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">3</span>
          <div>
            <p class="font-medium text-gray-900">Enable Custom URL</p>
            <p class="text-gray-900">Open the sidebar and go to <strong>Custom URL</strong>. Turn on <strong>Log to custom URL</strong>.</p>
          </div>
        </div>
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">4</span>
          <div>
            <p class="font-medium text-gray-900">Discard offline locations</p>
            <p class="text-gray-900">In the Custom URL settings, turn on <strong>Discard offline locations</strong>. Points are then sent only when the device has a network connection, which is recommended for live tracking so the map updates in real time and no points are queued while offline.</p>
          </div>
        </div>
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">5</span>
          <div class="flex-1 min-w-0">
            <p class="font-medium text-gray-900">URL</p>
            <p class="text-gray-900 mb-1">Tap <strong>URL</strong> and paste this address:</p>
            <div class="flex gap-2">
              <input :value="ingressUrl" readonly class="flex-1 px-2 py-1.5 text-sm border rounded bg-gray-50 min-w-0" />
              <button type="button" class="px-2 py-1.5 bg-gray-200 rounded text-sm flex-shrink-0" @click="copy(ingressUrl)">Copy</button>
            </div>
          </div>
        </div>
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">6</span>
          <div class="flex-1 min-w-0">
            <p class="font-medium text-gray-900">HTTP Body</p>
            <p class="text-gray-900 mb-1">Tap <strong>HTTP Body</strong> and paste:</p>
            <div class="flex gap-2">
              <input :value="bodyTemplate" readonly class="flex-1 px-2 py-1.5 text-sm border rounded bg-gray-50 min-w-0" />
              <button type="button" class="px-2 py-1.5 bg-gray-200 rounded text-sm flex-shrink-0" @click="copy(bodyTemplate)">Copy</button>
            </div>
          </div>
        </div>
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">7</span>
          <div class="flex-1 min-w-0">
            <p class="font-medium text-gray-900">HTTP Method</p>
            <p class="text-gray-900 mb-1">Tap <strong>HTTP Method</strong> and set to <strong>POST</strong>.</p>
            <div class="flex gap-2">
              <input value="POST" readonly class="flex-1 px-2 py-1.5 text-sm border rounded bg-gray-50 w-24" />
              <button type="button" class="px-2 py-1.5 bg-gray-200 rounded text-sm" @click="copy('POST')">Copy</button>
            </div>
          </div>
        </div>
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">8</span>
          <div class="flex-1 min-w-0 space-y-2">
            <p class="font-medium text-gray-900">Basic Authentication</p>
            <p class="text-gray-900">Tap <strong>Basic Authentication</strong> and set:</p>
            <div>
              <p class="text-gray-900 mb-1">Username:</p>
              <div class="flex gap-2">
                <input :value="username" readonly class="flex-1 px-2 py-1.5 text-sm border rounded bg-gray-50 min-w-0" />
                <button type="button" class="px-2 py-1.5 bg-gray-200 rounded text-sm flex-shrink-0" @click="copy(username)">Copy</button>
              </div>
            </div>
            <div>
              <p class="text-gray-900 mb-1">Password:</p>
              <div class="flex gap-2">
                <input :value="password" readonly class="flex-1 px-2 py-1.5 text-sm border rounded bg-gray-50 min-w-0" />
                <button type="button" class="px-2 py-1.5 bg-gray-200 rounded text-sm flex-shrink-0" @click="copy(password)">Copy</button>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="flex justify-end pt-2">
        <BaseButton variant="primary" color="blue" size="sm" @click="$emit('close')">Close</BaseButton>
      </div>
    </div>
  </BaseModal>
</template>

<script>
import BaseModal from 'platform/components/parts/BaseModal.vue';

export default {
  name: 'GpsLoggerInstructionsModal',
  components: { BaseModal },
  props: {
    ingressUrl: { type: String, default: '' },
    bodyTemplate: { type: String, default: 'lat=%LAT&lon=%LON&timestamp=%TIMESTAMP' },
    username: { type: String, default: '' },
    password: { type: String, default: '' }
  },
  emits: ['close'],
  setup() {
    function copy(text) {
      const toast = window.gv_core?.GeoVault?.toast;
      const showCopied = () => toast && toast.success('Copied');
      if (navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(text).then(showCopied).catch(() => copyFallback(text, showCopied));
      } else {
        copyFallback(text, showCopied);
      }
    }
    function copyFallback(text, onSuccess) {
      const el = document.createElement('textarea');
      el.value = text;
      el.setAttribute('readonly', '');
      el.style.position = 'fixed';
      el.style.left = '-9999px';
      el.style.top = '0';
      document.body.appendChild(el);
      el.select();
      el.setSelectionRange(0, text.length);
      try {
        if (document.execCommand('copy')) onSuccess?.();
      } catch (e) {
        // ignore
      }
      document.body.removeChild(el);
    }
    return { copy };
  }
};
</script>
