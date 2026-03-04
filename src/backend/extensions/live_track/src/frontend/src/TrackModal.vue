<template>
  <BaseModal
    :is-open="true"
    :title="mode === 'create' ? 'New track' : 'Edit track'"
    @close="$emit('close')"
  >
    <div class="p-4 space-y-4">
      <!-- After create: show credentials + Instructions -->
      <div v-if="mode === 'create' && createdTrack" class="space-y-3">
        <p class="text-sm text-amber-800 bg-amber-50 p-3 rounded">
          Keep your tracker password secret. Anyone who has it can send location data to this track.
        </p>
        <div class="space-y-2">
          <label class="text-sm font-medium text-gray-700">URL</label>
          <div class="flex gap-2">
            <input :value="ingressUrl" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
            <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy(ingressUrl)">Copy</button>
          </div>
        </div>
        <div class="space-y-2">
          <label class="text-sm font-medium text-gray-700">Method</label>
          <div class="flex gap-2">
            <input value="POST" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
            <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy('POST')">Copy</button>
          </div>
        </div>
        <div class="space-y-2">
          <label class="text-sm font-medium text-gray-700">Body</label>
          <div class="flex gap-2">
            <input :value="bodyTemplate" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
            <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy(bodyTemplate)">Copy</button>
          </div>
        </div>
        <div class="space-y-2">
          <label class="text-sm font-medium text-gray-700">Username</label>
          <div class="flex gap-2">
            <input :value="userLogin" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
            <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy(userLogin)">Copy</button>
          </div>
        </div>
        <div class="space-y-2">
          <label class="text-sm font-medium text-gray-700">Password (tracker secret)</label>
          <div class="flex gap-2">
            <input :value="createdTrack.tracker_secret" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
            <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy(createdTrack.tracker_secret)">Copy</button>
          </div>
        </div>
        <BaseButton variant="primary" color="blue" size="sm" @click="showInstructions = true">
          Instructions (GPSLogger)
        </BaseButton>
      </div>

      <!-- Create form -->
      <template v-else-if="mode === 'create'">
        <div class="space-y-2">
          <label class="text-sm font-medium text-gray-700">Name <span class="text-red-500">*</span></label>
          <input
            v-model="name"
            type="text"
            placeholder="Track name"
            class="w-full border border-gray-300 px-3 py-2 rounded-lg"
          />
          <p v-if="error" class="text-sm text-red-600">{{ error }}</p>
        </div>
        <div class="space-y-2">
          <label class="text-sm font-medium text-gray-700">Color</label>
          <ColorPickerElement
            :model-value="displayColor"
            @update:model-value="onColorPicked"
          />
        </div>
      </template>

      <!-- Edit form -->
      <template v-else>
        <div class="space-y-2">
          <label class="text-sm font-medium text-gray-700">Name <span class="text-red-500">*</span></label>
          <input
            v-model="name"
            type="text"
            placeholder="Track name"
            class="w-full border border-gray-300 px-3 py-2 rounded-lg"
          />
          <p v-if="error" class="text-sm text-red-600">{{ error }}</p>
        </div>
        <div class="space-y-2">
          <label class="text-sm font-medium text-gray-700">Password (read-only)</label>
          <div class="flex gap-2">
            <input :value="track?.tracker_secret" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
            <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy(track?.tracker_secret || '')">Copy</button>
          </div>
        </div>
        <div class="space-y-2">
          <label class="text-sm font-medium text-gray-700">Color</label>
          <div class="flex items-center gap-2">
            <ColorPickerElement v-model="color" />
            <button
              type="button"
              title="Reset to default color from name"
              class="p-2 rounded-lg text-gray-500 hover:bg-gray-100 hover:text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
              @click="resetColorToDeterministic"
            >
              <ArrowPathIcon class="h-5 w-5" />
            </button>
          </div>
        </div>
        <p class="text-sm text-amber-800 bg-amber-50 p-2 rounded">
          Anyone with the tracker password can send location data to this track.
        </p>
        <div class="flex flex-wrap gap-2">
          <BaseButton variant="white" size="sm" @click="showInstructions = true">Instructions (GPSLogger)</BaseButton>
          <BaseButton variant="white" size="sm" @click="downloadKml">Download KML</BaseButton>
          <BaseButton variant="secondary" color="red" size="sm" :disabled="deleting" @click="confirmDelete">
            <Loader v-if="deleting" size="sm" layout="inline" :show-message="false" class="mr-1" />
            Delete
          </BaseButton>
        </div>
      </template>
    </div>

    <template #actions>
      <template v-if="mode === 'create' && createdTrack">
        <BaseButton variant="white" size="sm" @click="$emit('saved')">Done</BaseButton>
      </template>
      <template v-else-if="mode === 'create'">
        <BaseButton variant="white" size="sm" @click="$emit('close')">Cancel</BaseButton>
        <BaseButton variant="primary" color="blue" size="sm" :disabled="saving || !name.trim()" @click="create">
          <Loader v-if="saving" size="sm" layout="inline" :show-message="false" class="mr-1" />
          Create
        </BaseButton>
      </template>
      <template v-else>
        <BaseButton variant="white" size="sm" @click="$emit('close')">Cancel</BaseButton>
        <BaseButton variant="primary" color="blue" size="sm" :disabled="saving || !name.trim()" @click="save">
          <Loader v-if="saving" size="sm" layout="inline" :show-message="false" class="mr-1" />
          Save
        </BaseButton>
      </template>
    </template>

    <GpsLoggerInstructionsModal
      v-if="showInstructions && instructionsPassword"
      :ingress-url="instructionsIngressUrl"
      :body-template="bodyTemplate"
      :username="userLogin"
      :password="instructionsPassword"
      @close="showInstructions = false"
    />
  </BaseModal>
</template>

<script>
import { ref, watch, computed, inject } from 'vue';
import { ArrowPathIcon } from '@heroicons/vue/24/outline';
import GpsLoggerInstructionsModal from './GpsLoggerInstructionsModal.vue';

export default {
  name: 'TrackModal',
  components: { GpsLoggerInstructionsModal, ArrowPathIcon },
  props: {
    mode: { type: String, required: true },
    track: { type: Object, default: null },
    userLogin: { type: String, default: '' }
  },
  emits: ['close', 'saved', 'deleted'],
  setup(props, { emit }) {
    const api = inject('extensionApi');
    const name = ref('');
    const color = ref('#3388ff');
    const userPickedColor = ref(false);
    const error = ref('');
    const saving = ref(false);
    const deleting = ref(false);
    const createdTrack = ref(null);
    const showInstructions = ref(false);

    /** Deterministic hex color from string (high S, high V so not too dark). */
    function colorFromName(str) {
      let h = 0;
      for (let i = 0; i < str.length; i++) {
        h = ((h << 5) - h) + str.charCodeAt(i);
        h = h & h;
      }
      const hue = (Math.abs(h) >>> 0) % 360;
      const s = 0.7, v = 0.95;
      const c = v * s;
      const x = c * (1 - Math.abs(((hue / 60) % 2) - 1));
      const m = v - c;
      let r, g, b;
      if (hue < 60) { r = c; g = x; b = 0; }
      else if (hue < 120) { r = x; g = c; b = 0; }
      else if (hue < 180) { r = 0; g = c; b = x; }
      else if (hue < 240) { r = 0; g = x; b = c; }
      else if (hue < 300) { r = x; g = 0; b = c; }
      else { r = c; g = 0; b = x; }
      const toHex = (n) => Math.round((n + m) * 255).toString(16).padStart(2, '0');
      return '#' + toHex(r) + toHex(g) + toHex(b);
    }

    const displayColor = computed(() => {
      if (props.mode !== 'create') return color.value;
      if (userPickedColor.value) return color.value;
      return name.value.trim() ? colorFromName(name.value.trim()) : '#3388ff';
    });

    function onColorPicked(value) {
      color.value = value;
      userPickedColor.value = true;
    }

    function resetColorToDeterministic() {
      color.value = name.value.trim() ? colorFromName(name.value.trim()) : '#3388ff';
    }

    const baseUrl = typeof window !== 'undefined' ? `${window.location.origin}/api/extensions/live-track` : '';
    const bodyTemplate = 'lat=%LAT&lon=%LON&time=%TIME';
    const ingressUrl = computed(() => (createdTrack.value ? `${baseUrl}/ingress/` : ''));
    const ingressUrlEdit = computed(() => (props.track ? `${baseUrl}/ingress/` : ''));
    const instructionsIngressUrl = computed(() => `${baseUrl}/ingress/`);
    const instructionsPassword = computed(() => createdTrack.value?.tracker_secret || props.track?.tracker_secret || '');

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

    async function create() {
      if (!api || saving.value || !name.value.trim()) return;
      error.value = '';
      saving.value = true;
      try {
        const res = await api.post('/trackers/', { name: name.value.trim(), color: displayColor.value });
        createdTrack.value = res.data;
      } catch (e) {
        const err = api.handleError?.(e);
        error.value = err?.message || 'Failed to create track';
      } finally {
        saving.value = false;
      }
    }

    async function save() {
      if (!api || !props.track || saving.value || !name.value.trim()) return;
      error.value = '';
      saving.value = true;
      try {
        await api.patch(`/trackers/${props.track.id}/`, { name: name.value.trim(), color: color.value });
        emit('saved');
      } catch (e) {
        const err = api.handleError?.(e);
        error.value = err?.message || 'Failed to save';
      } finally {
        saving.value = false;
      }
    }

    async function downloadKml() {
      if (!props.track || !api) return;
      try {
        const url = api.url(`trackers/${props.track.id}/kml/`);
        const token = document.cookie.match(/csrftoken=([^;]+)/)?.[1];
        const res = await fetch(url, {
          method: 'GET',
          credentials: 'include',
          headers: token ? { 'X-CSRFToken': token } : {}
        });
        if (!res.ok) throw new Error(res.statusText);
        const blob = await res.blob();
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `${(props.track.name || 'track').replace(/[^a-zA-Z0-9-_]/g, '_')}.kml`;
        a.click();
        URL.revokeObjectURL(a.href);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Download started');
      } catch (e) {
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(e?.message || 'Download failed');
      }
    }

    function confirmDelete() {
      if (!props.track || deleting.value) return;
      if (!confirm('Delete this track? This cannot be undone.')) return;
      deleting.value = true;
      api.delete(`/trackers/${props.track.id}/`).then(() => {
        emit('deleted');
      }).catch((e) => {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Delete failed');
      }).finally(() => {
        deleting.value = false;
      });
    }

    watch(() => props.track, (t) => {
      if (t) {
        name.value = t.name || '';
        color.value = t.color || '#3388ff';
        userPickedColor.value = true;
      } else {
        userPickedColor.value = false;
        color.value = '#3388ff';
      }
    }, { immediate: true });

    return {
      name,
      color,
      displayColor,
      onColorPicked,
      resetColorToDeterministic,
      error,
      saving,
      deleting,
      createdTrack,
      showInstructions,
      ingressUrl,
      ingressUrlEdit,
      bodyTemplate,
      instructionsIngressUrl,
      instructionsPassword,
      copy,
      create,
      save,
      downloadKml,
      confirmDelete
    };
  }
};
</script>
