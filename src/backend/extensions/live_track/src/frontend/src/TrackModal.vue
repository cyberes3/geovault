<template>
  <BaseModal
    :is-open="true"
    :title="modalTitle"
    :close-on-escape="false"
    @close="$emit('close')"
  >
    <CreateSuccessView
      v-if="mode === 'create' && createdTrack"
      :ingress-url="ingressUrl"
      :body-template="bodyTemplate"
      :user-login="userLogin"
      :tracker-secret="createdTrack?.tracker_secret ?? ''"
      :copy="copy"
      @open-instructions="showInstructions = true"
    />
    <CreateTrackForm
      v-else-if="mode === 'create'"
      :name="name"
      :display-color="displayColor"
      :error="error"
      @update:name="name = $event"
      @color-picked="onColorPicked"
    />
    <div v-else-if="mode === 'edit' && (loading || !track)" class="flex items-center justify-center py-12">
      <Loader size="md" message="Loading track..." />
    </div>
    <EditTrackForm
      v-else
      :track="track"
      :name="name"
      :color="color"
      :recent-data-window="recentDataWindow"
      :error="error"
      :deleting="deleting"
      :clearing="clearing"
      :clear-history-disabled="clearing || historyClearedThisSession"
      :copy="copy"
      @update:name="name = $event"
      @update:color="color = $event"
      @update:recentDataWindow="recentDataWindow = $event"
      @reset-color="resetColorToDeterministic"
      @open-instructions="showInstructions = true"
      @download-kml="downloadKml"
      @clear-history="confirmClearHistory"
      @delete="confirmDelete"
    />

    <template #actions>
      <template v-if="mode === 'create' && createdTrack">
        <BaseButton variant="white" size="sm" @click="$emit('saved')">Close</BaseButton>
      </template>
      <template v-else-if="mode === 'create'">
        <BaseButton variant="white" size="sm" @click="$emit('close')">Close</BaseButton>
        <BaseButton variant="primary" color="blue" size="sm" :disabled="saving || !name.trim()" @click="create">
          <Loader v-if="saving" size="sm" layout="inline" :show-message="false" class="mr-1" />
          Create
        </BaseButton>
      </template>
      <template v-else-if="mode === 'edit' && (loading || !track)">
        <BaseButton variant="white" size="sm" @click="$emit('close')">Close</BaseButton>
      </template>
      <template v-else>
        <BaseButton variant="white" size="sm" @click="$emit('close')">Close</BaseButton>
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
      :profile-url="profileUrl"
      @close="showInstructions = false"
    />
  </BaseModal>
</template>

<script>
import { ref, watch, computed, inject, onMounted } from 'vue';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import Loader from 'platform/components/parts/Loader.vue';
import { getIngressBodyTemplate } from './ingressBodyTemplateCache.js';
import CreateTrackForm from './CreateTrackForm.vue';
import CreateSuccessView from './CreateSuccessView.vue';
import EditTrackForm from './EditTrackForm.vue';
import GpsLoggerInstructionsModal from './GpsLoggerInstructionsModal.vue';

export default {
  name: 'TrackModal',
  components: { BaseModal, Loader, CreateTrackForm, CreateSuccessView, EditTrackForm, GpsLoggerInstructionsModal },
  props: {
    mode: { type: String, required: true },
    track: { type: Object, default: null },
    loading: { type: Boolean, default: false },
    userLogin: { type: String, default: '' }
  },
  emits: ['close', 'saved', 'deleted'],
  setup(props, { emit }) {
    const api = inject('extensionApi');
    const name = ref('');
    const color = ref('#3388ff');
    const recentDataWindow = ref('');
    const userPickedColor = ref(false);
    const error = ref('');
    const saving = ref(false);
    const deleting = ref(false);
    const clearing = ref(false);
    /** After clear history succeeds, keep the clear button disabled until the modal is closed. */
    const historyClearedThisSession = ref(false);
    const createdTrack = ref(null);
    const showInstructions = ref(false);

    const modalTitle = computed(() => (props.mode === 'create' ? 'New track' : 'Edit track'));

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
    const bodyTemplate = ref('lat=%LAT&lon=%LON&timestamp=%TIMESTAMP');
    onMounted(async () => {
      const data = await getIngressBodyTemplate(api);
      if (data?.body_template) bodyTemplate.value = data.body_template;
    });
    const ingressUrl = computed(() => (createdTrack.value ? `${baseUrl}/ingress/` : ''));
    const instructionsIngressUrl = computed(() => `${baseUrl}/ingress/`);
    const instructionsPassword = computed(() => createdTrack.value?.tracker_secret || props.track?.tracker_secret || '');
    const trackIdForProfile = computed(() => createdTrack.value?.id ?? props.track?.id);
    const profileDisplayName = computed(() => {
      const raw = (createdTrack.value?.name ?? props.track?.name ?? 'track').replace(/[^a-zA-Z0-9 \-_]/g, '').trim().slice(0, 41);
      const name = raw ? `GeoVault ${raw}`.trim() : 'GeoVault';
      return name;
    });
    const profileUrl = computed(() => {
      const id = trackIdForProfile.value;
      const secret = instructionsPassword.value;
      const name = profileDisplayName.value;
      if (!id || !secret) return '';
      return `${baseUrl}/trackers/${id}/${encodeURIComponent(name)}.properties?secret=${encodeURIComponent(secret)}`;
    });
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
        await api.post(`/trackers/${props.track.id}/settings/`, {
          name: name.value.trim(),
          color: color.value,
          recent_data_window: recentDataWindow.value || null
        });
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

    function confirmClearHistory() {
      if (!props.track || clearing.value) return;
      if (!confirm('Clear all track history except the latest point? This cannot be undone.')) return;
      clearing.value = true;
      api.post(`/trackers/${props.track.id}/clear-history/`).then(() => {
        historyClearedThisSession.value = true;
        emit('saved');
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('History cleared');
      }).catch((e) => {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Clear history failed');
      }).finally(() => {
        clearing.value = false;
      });
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
        recentDataWindow.value = t.settings?.recent_data_window ?? '';
        userPickedColor.value = true;
      } else {
        userPickedColor.value = false;
        color.value = '#3388ff';
        recentDataWindow.value = '';
      }
    }, { immediate: true });

    return {
      name,
      color,
      recentDataWindow,
      displayColor,
      onColorPicked,
      resetColorToDeterministic,
      error,
      saving,
      deleting,
      clearing,
      historyClearedThisSession,
      createdTrack,
      showInstructions,
      modalTitle,
      ingressUrl,
      bodyTemplate,
      instructionsIngressUrl,
      instructionsPassword,
      profileUrl,
      copy,
      create,
      save,
      downloadKml,
      confirmClearHistory,
      confirmDelete
    };
  }
};
</script>
