<template>
  <BaseModal :is-open="true" title="Configure GPSLogger" :on-top="true" @close="$emit('close')">
    <div class="p-4 space-y-6 text-sm">
      <p class="text-gray-900">
        In the GPSLogger app, follow these steps to send your location to this tracker.
      </p>
      <div v-if="profileUrl" class="space-y-3 p-3 bg-gray-50 rounded-lg border border-gray-200">
        <p class="font-medium text-gray-900">Import Profile</p>
        <p class="text-gray-700 text-xs">Scan the QR code or copy the profile URL to load this tracker’s settings into GPSLogger in one step.</p>
        <div class="flex flex-col sm:flex-row gap-4 items-start">
          <div v-if="qrDataUrl" class="flex-shrink-0">
            <img :src="qrDataUrl" alt="Open in GPSLogger (gpslogger://)" class="w-32 h-32 rounded border border-gray-300" />
          </div>
          <div class="flex-1 min-w-0 space-y-2">
            <div>
              <p class="text-gray-700 text-xs font-medium mb-1">Profile URL (paste in GPSLogger: From URL)</p>
              <div class="flex gap-2">
                <input :value="profileUrl" readonly class="flex-1 px-2 py-1.5 text-xs border rounded bg-white min-w-0" />
                <button type="button" class="px-2 py-1.5 bg-gray-200 rounded text-xs flex-shrink-0" @click="copy(profileUrl)">Copy</button>
              </div>
            </div>
            <div v-if="isMobile && gpsloggerOpenUrl" class="pt-1">
              <button
                type="button"
                class="px-3 py-2 bg-green-600 hover:bg-green-700 text-white text-sm font-medium rounded"
                @click="openInGpsLogger"
              >
                Open in GPSLogger
              </button>
            </div>
          </div>
        </div>
      </div>
      <div class="flex justify-center">
        <a
          href="https://f-droid.org/en/packages/com.mendhak.gpslogger/"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-block"
          aria-label="Get GPSLogger on F-Droid"
        >
          <img
            :src="fdroidBadgeUrl"
            alt="Get it on F-Droid"
            class="h-10 w-auto max-w-[180px] object-contain"
          />
        </a>
      </div>
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
              <li><strong>Only log if significant motion</strong> – when on, the app logs only after your device detects significant activity (e.g. walking, biking, driving). The device uses its built-in motion sensor; no points are recorded while you stay still, which saves battery. On some devices this option may be disabled if the sensor is not available.</li>
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
            <p class="font-medium text-gray-900">Discard Offline Locations</p>
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
    </div>
  </BaseModal>
</template>

<script>
import { ref, watch, computed } from 'vue';
import QRCode from 'qrcode';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import fdroidBadgeUrl from '@/assets/get-it-on-fdroid.svg';

export default {
  name: 'GpsLoggerInstructionsModal',
  components: { BaseModal },
  props: {
    ingressUrl: { type: String, default: '' },
    bodyTemplate: { type: String, default: 'lat=%LAT&lon=%LON&timestamp=%TIMESTAMP' },
    username: { type: String, default: '' },
    password: { type: String, default: '' },
    profileUrl: { type: String, default: '' }
  },
  emits: ['close'],
  setup(props) {
    const qrDataUrl = ref('');
    // gpslogger:// link is exactly profileUrl with scheme prefix so it always matches "Profile URL (paste in GPSLogger: From URL)"
    const gpsloggerOpenUrl = computed(() =>
      props.profileUrl ? `gpslogger://properties/${props.profileUrl}` : ''
    );
    watch(() => gpsloggerOpenUrl.value, (url) => {
      if (!url) {
        qrDataUrl.value = '';
        return;
      }
      QRCode.toDataURL(url, { width: 256, margin: 1 }).then((dataUrl) => {
        qrDataUrl.value = dataUrl;
      }).catch(() => {
        qrDataUrl.value = '';
      });
    }, { immediate: true });
    const isMobile = typeof navigator !== 'undefined' && /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
    function openInGpsLogger() {
      if (gpsloggerOpenUrl.value) {
        window.location.href = gpsloggerOpenUrl.value;
      }
    }
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
    return { copy, qrDataUrl, fdroidBadgeUrl, isMobile, openInGpsLogger, gpsloggerOpenUrl };
  }
};
</script>
