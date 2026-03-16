<template>
  <LiveTrackSidebar
    v-if="!embedded"
    :title="sidebarTitle"
    :container-ref="containerRef"
    @close="$emit('close')"
  >
    <CreateSuccessView
      v-if="mode === 'create' && createdTrack"
      :ingress-url="ingressUrl"
      :body-template="bodyTemplate"
      :user-login="userLogin"
      :tracker-secret="createdTrack?.tracker_secret ?? ''"
      :copy="copy"
      :hauk-domain="haukDomain"
      @open-instructions="showInstructions = true"
      @open-hauk-instructions="showHaukInstructions = true"
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
      <Loader size="md" message="Loading tracker..." />
    </div>
    <EditTrackForm
      v-else
      :track="track"
      :name="name"
      :color="color"
      :recent-data-window="recentDataWindow"
      :visibility="visibility"
      :share-params-with-recipients="shareParamsWithRecipients"
      :share-params-with-world="shareParamsWithWorld"
      :shared-with-emails="sharedWithEmails"
      :is-owner="isOwner"
      :error="error"
      :deleting="deleting"
      :clearing="clearing"
      :regenerating-tokens="regeneratingTokens"
      :unsubscribing="unsubscribing"
      :clear-history-disabled="clearing || historyClearedThisSession"
      :copy="copy"
      :tracker-secret="effectiveTrackerSecret"
      :world-share-enabled="worldShareEnabled"
      :world-share-url="worldShareUrl"
      :hidden-in-list="hiddenInList"
      :allow-group-reshare="allowGroupReshare"
      :hauk-domain="haukDomain"
      @update:name="name = $event"
      @update:color="color = $event"
      @update:recentDataWindow="recentDataWindow = $event"
      @update:visibility="visibility = $event"
      @update:shareParamsWithRecipients="onShareParamsWithRecipientsUpdate($event)"
      @update:shareParamsWithWorld="shareParamsWithWorld = $event"
      @update:sharedWithEmails="sharedWithEmails = $event"
      @update:worldShareEnabled="setWorldShareEnabled"
      @update:allowGroupReshare="onAllowGroupReshareChange($event)"
      @update:hidden-in-list="onHiddenInListChange($event)"
      @reset-color="resetColorToDeterministic"
      @open-instructions="showInstructions = true"
      @open-hauk-instructions="showHaukInstructions = true"
      @download-kml="downloadKml"
      @clear-history="confirmClearHistory"
      @regenerate-tokens="confirmRegenerateTokens"
      @delete="confirmDelete"
      @unsubscribe="confirmUnsubscribe"
    />

    <template #actions>
      <template v-if="mode === 'create' && !createdTrack">
        <BaseButton variant="primary" color="blue" size="sm" :disabled="saving || !name.trim()" @click="create">
          <Loader v-if="saving" size="sm" layout="inline" :show-message="false" class="mr-1" />
          Create
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
    <HaukInstructionsModal
      v-if="showHaukInstructions && haukDomain && haukServerUrl && userLogin && haukPassword"
      :server-url="haukServerUrl"
      :user-email="userLogin"
      :hauk-password="haukPassword"
      @close="showHaukInstructions = false"
    />
  </LiveTrackSidebar>
  <!-- Embedded: content + footer only (shell is provided by parent) -->
  <div v-else class="flex-1 min-h-0 flex flex-col">
    <div class="flex-1 overflow-y-auto min-h-0 px-4 sm:px-5 py-4">
      <CreateSuccessView
        v-if="mode === 'create' && createdTrack"
        :ingress-url="ingressUrl"
        :body-template="bodyTemplate"
        :user-login="userLogin"
        :tracker-secret="createdTrack?.tracker_secret ?? ''"
        :copy="copy"
        :hauk-domain="haukDomain"
        @open-instructions="showInstructions = true"
        @open-hauk-instructions="showHaukInstructions = true"
      />
      <CreateTrackForm
        v-else-if="mode === 'create'"
        :name="name"
        :display-color="displayColor"
        :error="error"
        @update:name="name = $event"
        @color-picked="onColorPicked"
      />
      <div v-else-if="mode === 'edit' && (loading || !track)" class="flex justify-center py-12">
        <Loader size="md" message="Loading tracker..." />
      </div>
      <EditTrackForm
        v-else
        :track="track"
        :name="name"
        :color="color"
        :recent-data-window="recentDataWindow"
        :visibility="visibility"
        :share-params-with-recipients="shareParamsWithRecipients"
        :share-params-with-world="shareParamsWithWorld"
        :shared-with-emails="sharedWithEmails"
        :is-owner="isOwner"
        :error="error"
        :deleting="deleting"
        :clearing="clearing"
        :regenerating-tokens="regeneratingTokens"
        :unsubscribing="unsubscribing"
        :clear-history-disabled="clearing || historyClearedThisSession"
        :copy="copy"
        :tracker-secret="effectiveTrackerSecret"
        :world-share-enabled="worldShareEnabled"
        :world-share-url="worldShareUrl"
        :hidden-in-list="hiddenInList"
        :allow-group-reshare="allowGroupReshare"
        :hauk-domain="haukDomain"
        @update:name="name = $event"
        @update:color="color = $event"
        @update:recentDataWindow="recentDataWindow = $event"
        @update:visibility="visibility = $event"
        @update:shareParamsWithRecipients="onShareParamsWithRecipientsUpdate($event)"
        @update:shareParamsWithWorld="shareParamsWithWorld = $event"
        @update:sharedWithEmails="sharedWithEmails = $event"
        @update:worldShareEnabled="setWorldShareEnabled"
        @update:allowGroupReshare="onAllowGroupReshareChange($event)"
        @update:hidden-in-list="onHiddenInListChange($event)"
        @reset-color="resetColorToDeterministic"
        @open-instructions="showInstructions = true"
        @open-hauk-instructions="showHaukInstructions = true"
        @download-kml="downloadKml"
        @clear-history="confirmClearHistory"
        @regenerate-tokens="confirmRegenerateTokens"
        @delete="confirmDelete"
        @unsubscribe="confirmUnsubscribe"
      />
    </div>
    <div
      v-if="mode === 'create' && !createdTrack"
      class="flex-shrink-0 px-4 sm:px-5 py-3 border-t border-gray-200 bg-gray-50 flex items-center justify-end gap-3"
    >
      <BaseButton
        variant="primary"
        color="blue"
        size="sm"
        :disabled="saving || !name.trim()"
        @click="create"
      >
        <Loader v-if="saving" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Create
      </BaseButton>
    </div>
    <GpsLoggerInstructionsModal
      v-if="showInstructions && instructionsPassword"
      :ingress-url="instructionsIngressUrl"
      :body-template="bodyTemplate"
      :username="userLogin"
      :password="instructionsPassword"
      :profile-url="profileUrl"
      @close="showInstructions = false"
    />
    <HaukInstructionsModal
      v-if="showHaukInstructions && haukDomain && haukServerUrl && userLogin && haukPassword"
      :server-url="haukServerUrl"
      :user-email="userLogin"
      :hauk-password="haukPassword"
      @close="showHaukInstructions = false"
    />
  </div>
</template>

<script>
import { ref, watch, computed, inject, onMounted, onBeforeUnmount } from 'vue';
import LiveTrackSidebar from './LiveTrackSidebar.vue';
import Loader from 'platform/components/parts/Loader.vue';
import { getIngressBodyTemplate } from './ingressBodyTemplateCache.js';
import CreateTrackForm from './CreateTrackForm.vue';
import CreateSuccessView from './CreateSuccessView.vue';
import EditTrackForm from './EditTrackForm.vue';
import GpsLoggerInstructionsModal from './GpsLoggerInstructionsModal.vue';
import HaukInstructionsModal from './HaukInstructionsModal.vue';

export default {
  name: 'TrackSidebar',
  components: { LiveTrackSidebar, Loader, CreateTrackForm, CreateSuccessView, EditTrackForm, GpsLoggerInstructionsModal, HaukInstructionsModal },
  props: {
    mode: { type: String, required: true },
    track: { type: Object, default: null },
    loading: { type: Boolean, default: false },
    userLogin: { type: String, default: '' },
    containerRef: { type: Object, default: null },
    /** When true, render only content + footer (no sidebar shell); parent provides the shell. */
    embedded: { type: Boolean, default: false },
  },
  emits: ['close', 'saved', 'deleted', 'unsubscribed', 'settings-changed'],
  setup(props, { emit }) {
    const AUTOSAVE_DEBOUNCE_MS = 500;
    const api = inject('extensionApi');
    const name = ref('');
    const color = ref('#6C93DE');
    const recentDataWindow = ref('');
    const visibility = ref('private');
    const shareParamsWithRecipients = ref(false);
    const sharedWithEmails = ref([]);
    const userPickedColor = ref(false);
    const unsubscribing = ref(false);
    const error = ref('');
    const saving = ref(false);
    const deleting = ref(false);
    const clearing = ref(false);
    const regeneratingTokens = ref(false);
    /** After clear history succeeds, keep the clear button disabled until the sidebar is closed. */
    const historyClearedThisSession = ref(false);
    const worldShareEnabled = ref(false);
    const worldShareUrl = ref('');
    const shareParamsWithWorld = ref(false);
    const hiddenInList = ref(false);
    const allowGroupReshare = ref(false);
    const lastSavedSnapshot = ref(null);
    const isInitializingDraft = ref(false);
    const createdTrack = ref(null);
    const showInstructions = ref(false);
    const showHaukInstructions = ref(false);
    const haukDomain = ref('');
    const trackerSecretOverride = ref('');
    const haukPasswordOverride = ref('');
    let autosaveTimerId = null;
    let autosaveInFlight = false;
    let autosaveQueued = false;
    let autosaveSeq = 0;

    const sidebarTitle = computed(() => (props.mode === 'create' ? 'New Tracker' : 'Edit Tracker'));
    const isOwner = computed(() => props.track?.is_owner !== false);

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
      return name.value.trim() ? colorFromName(name.value.trim()) : '#6C93DE';
    });

    function onColorPicked(value) {
      color.value = value;
      userPickedColor.value = true;
    }

    function resetColorToDeterministic() {
      color.value = name.value.trim() ? colorFromName(name.value.trim()) : '#6C93DE';
    }

    const baseUrl = typeof window !== 'undefined' ? `${window.location.origin}/api/extensions/live-track` : '';
    const bodyTemplate = ref('lat=%LAT&lon=%LON&timestamp=%TIMESTAMP');
    const haukServerUrl = computed(() => {
      const d = (haukDomain.value || '').trim();
      if (!d) return '';
      if (/^https?:\/\//i.test(d)) return d.replace(/\/+$/, '');
      return `https://${d}`;
    });
    const effectiveTrackerSecret = computed(() => trackerSecretOverride.value || props.track?.tracker_secret || '');
    const effectiveHaukPassword = computed(() => haukPasswordOverride.value || props.track?.hauk_password || createdTrack.value?.hauk_password || '');
    const haukPassword = computed(() => effectiveHaukPassword.value);
    onMounted(async () => {
      const data = await getIngressBodyTemplate(api);
      if (data?.body_template) bodyTemplate.value = data.body_template;
      try {
        const res = await api.get('hauk-config/');
        const domain = (res?.data?.hauk_domain ?? '').trim();
        if (domain) haukDomain.value = domain;
      } catch {
        // hauk_domain not configured or request failed; Hauk Setup button will be hidden
      }
    });
    const ingressUrl = computed(() => (createdTrack.value ? `${baseUrl}/ingress/` : ''));
    const instructionsIngressUrl = computed(() => `${baseUrl}/ingress/`);
    const instructionsPassword = computed(() => createdTrack.value?.tracker_secret || effectiveTrackerSecret.value || '');
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

    function makeSnapshotFromState() {
      return {
        name: name.value,
        color: color.value,
        recentDataWindow: recentDataWindow.value || '',
        visibility: visibility.value || 'private',
        shareParamsWithRecipients: shareParamsWithRecipients.value === true,
        shareParamsWithWorld: shareParamsWithWorld.value === true,
        sharedWithEmails: [...(sharedWithEmails.value || [])].map((e) => String(e || '').toLowerCase()),
        worldShareEnabled: worldShareEnabled.value === true,
        hiddenInList: hiddenInList.value === true,
        allowGroupReshare: allowGroupReshare.value === true
      };
    }

    function normalizeSnapshot(snapshot) {
      if (!snapshot) return null;
      return {
        name: String(snapshot.name || ''),
        color: String(snapshot.color || '#6C93DE'),
        recentDataWindow: String(snapshot.recentDataWindow || ''),
        visibility: String(snapshot.visibility || 'private'),
        shareParamsWithRecipients: snapshot.shareParamsWithRecipients === true,
        shareParamsWithWorld: snapshot.shareParamsWithWorld === true,
        sharedWithEmails: [...(snapshot.sharedWithEmails || [])]
          .map((e) => String(e || '').trim().toLowerCase())
          .filter(Boolean)
          .sort(),
        worldShareEnabled: snapshot.worldShareEnabled === true,
        hiddenInList: snapshot.hiddenInList === true,
        allowGroupReshare: snapshot.allowGroupReshare === true
      };
    }

    function snapshotsEqual(a, b) {
      return JSON.stringify(normalizeSnapshot(a)) === JSON.stringify(normalizeSnapshot(b));
    }

    function buildSettingsPayload(snapshot) {
      const payload = {
        name: snapshot.name.trim(),
        color: snapshot.color,
        recent_data_window: snapshot.recentDataWindow || null,
        visibility: snapshot.visibility,
        share_params_with_recipients: snapshot.shareParamsWithRecipients,
        share_params_with_world: snapshot.shareParamsWithWorld,
        world_share_enabled: snapshot.worldShareEnabled,
        hidden_in_list: snapshot.hiddenInList,
        allow_group_reshare: snapshot.allowGroupReshare
      };
      if (snapshot.visibility === 'shared') {
        payload.shared_with_emails = snapshot.sharedWithEmails;
      }
      return payload;
    }

    function stopAutosaveTimer() {
      if (autosaveTimerId != null) {
        clearTimeout(autosaveTimerId);
        autosaveTimerId = null;
      }
    }

    function queueAutosave() {
      if (props.mode !== 'edit' || !isOwner.value || !props.track?.id) return;
      if (isInitializingDraft.value) return;
      if (!lastSavedSnapshot.value) return;
      const current = makeSnapshotFromState();
      if (snapshotsEqual(current, lastSavedSnapshot.value)) return;
      stopAutosaveTimer();
      autosaveTimerId = setTimeout(() => {
        autosaveTimerId = null;
        flushAutosave().catch(() => {});
      }, AUTOSAVE_DEBOUNCE_MS);
    }

    async function flushAutosave() {
      if (props.mode !== 'edit' || !isOwner.value || !props.track?.id) return;
      if (!lastSavedSnapshot.value) return;
      const current = makeSnapshotFromState();
      if (snapshotsEqual(current, lastSavedSnapshot.value)) return;
      if (autosaveInFlight) {
        autosaveQueued = true;
        return;
      }
      autosaveInFlight = true;
      const seq = ++autosaveSeq;
      error.value = '';
      saving.value = true;
      try {
        const payload = buildSettingsPayload(current);
        const res = await api.post(`/trackers/${props.track.id}/settings/`, payload);
        if (seq !== autosaveSeq) return;
        lastSavedSnapshot.value = makeSnapshotFromState();
        if (res?.data) {
          worldShareEnabled.value = !!(res.data.world_share_id);
          worldShareUrl.value = res.data.world_share_url || '';
        }
      } catch (e) {
        if (seq === autosaveSeq) {
          const err = api.handleError?.(e);
          error.value = err?.message || 'Failed to save';
        }
      } finally {
        if (seq === autosaveSeq) saving.value = false;
        autosaveInFlight = false;
        if (autosaveQueued) {
          autosaveQueued = false;
          await flushAutosave();
        }
      }
    }

    async function create() {
      if (!api || saving.value || !name.value.trim()) return;
      error.value = '';
      saving.value = true;
      try {
        const res = await api.post('/trackers/', { name: name.value.trim(), color: displayColor.value });
        createdTrack.value = res.data;
        emit('saved', { action: 'created', tracker: res?.data || null });
      } catch (e) {
        const err = api.handleError?.(e);
        error.value = err?.message || 'Failed to create tracker';
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
      if (!confirm('Clear all tracker history except the latest point? This cannot be undone.')) return;
      clearing.value = true;
      api.post(`/trackers/${props.track.id}/clear-history/`).then(() => {
        historyClearedThisSession.value = true;
        emit('saved', { action: 'history-cleared', trackId: props.track?.id ?? null });
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
      if (!confirm('Delete this tracker? This cannot be undone.')) return;
      deleting.value = true;
      api.delete(`/trackers/${props.track.id}/`).then(() => {
        emit('deleted', { trackId: props.track?.id ?? null });
      }).catch((e) => {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Delete failed');
      }).finally(() => {
        deleting.value = false;
      });
    }

    async function confirmRegenerateTokens() {
      if (!props.track?.id || regeneratingTokens.value) return;
      if (!confirm('Regenerate all tracker tokens (API and Hauk)? Existing integrations using old tokens will stop working until updated.')) return;
      regeneratingTokens.value = true;
      try {
        const res = await api.post(`/trackers/${props.track.id}/regenerate-tokens/`);
        const nextSecret = String(res?.data?.tracker_secret || '');
        const nextHaukPassword = String(res?.data?.hauk_password || '');
        if (nextSecret) trackerSecretOverride.value = nextSecret;
        if (nextHaukPassword) haukPasswordOverride.value = nextHaukPassword;
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Tracker tokens regenerated');
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to regenerate tokens');
      } finally {
        regeneratingTokens.value = false;
      }
    }

    watch(() => props.track, (t) => {
      isInitializingDraft.value = true;
      trackerSecretOverride.value = '';
      haukPasswordOverride.value = '';
      if (t) {
        name.value = t.name || '';
        color.value = t.color || '#6C93DE';
        recentDataWindow.value = t.settings?.recent_data_window ?? '';
        visibility.value = t.visibility || 'private';
        shareParamsWithRecipients.value = t.share_params_with_recipients === true;
        shareParamsWithWorld.value = t.share_params_with_world === true;
        sharedWithEmails.value = Array.isArray(t.shared_with_emails) ? [...t.shared_with_emails] : [];
        worldShareEnabled.value = !!(t.world_share_id);
        worldShareUrl.value = t.world_share_url || '';
        hiddenInList.value = (t.settings && t.settings.hidden_in_list) === true;
        allowGroupReshare.value = (t.settings && t.settings.allow_group_reshare) === true;
        userPickedColor.value = true;
      } else {
        userPickedColor.value = false;
        color.value = '#6C93DE';
        recentDataWindow.value = '';
        visibility.value = 'private';
        shareParamsWithRecipients.value = false;
        shareParamsWithWorld.value = false;
        sharedWithEmails.value = [];
        worldShareEnabled.value = false;
        worldShareUrl.value = '';
        hiddenInList.value = false;
        allowGroupReshare.value = false;
      }
      lastSavedSnapshot.value = makeSnapshotFromState();
      isInitializingDraft.value = false;
    }, { immediate: true });

    function setWorldShareEnabled(enabled) {
      worldShareEnabled.value = enabled === true;
      queueAutosave();
    }

    function onHiddenInListChange(value) {
      hiddenInList.value = value;
      if (props.track?.id) {
        emit('settings-changed', { trackId: props.track.id, hidden_in_list: value });
      }
      queueAutosave();
    }

    function onAllowGroupReshareChange(value) {
      allowGroupReshare.value = value;
      queueAutosave();
    }

    function onShareParamsWithRecipientsUpdate(value) {
      shareParamsWithRecipients.value = value;
      queueAutosave();
    }

    watch(
      () => ({
        name: name.value,
        color: color.value,
        recentDataWindow: recentDataWindow.value,
        visibility: visibility.value,
        shareParamsWithRecipients: shareParamsWithRecipients.value,
        shareParamsWithWorld: shareParamsWithWorld.value,
        sharedWithEmails: sharedWithEmails.value,
        hiddenInList: hiddenInList.value,
        worldShareEnabled: worldShareEnabled.value,
        allowGroupReshare: allowGroupReshare.value
      }),
      () => {
        queueAutosave();
      },
      { deep: true }
    );

    onBeforeUnmount(() => {
      stopAutosaveTimer();
    });

    async function confirmUnsubscribe() {
      if (!props.track?.id || unsubscribing.value) return;
      if (!confirm('Remove this tracker from your list? You can add it again from Shared With Me.')) return;
      unsubscribing.value = true;
      try {
        await api.delete(`/trackers/${props.track.id}/subscribe/`);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Removed from list');
        emit('unsubscribed', props.track.id);
      } catch (e) {
        const err = api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to remove');
      } finally {
        unsubscribing.value = false;
      }
    }

    return {
      name,
      color,
      recentDataWindow,
      visibility,
      shareParamsWithRecipients,
      shareParamsWithWorld,
      sharedWithEmails,
      hiddenInList,
      worldShareEnabled,
      worldShareUrl,
      setWorldShareEnabled,
      onHiddenInListChange,
      onShareParamsWithRecipientsUpdate,
      isOwner,
      unsubscribing,
      displayColor,
      onColorPicked,
      resetColorToDeterministic,
      error,
      saving,
      deleting,
      clearing,
      regeneratingTokens,
      historyClearedThisSession,
      effectiveTrackerSecret,
      createdTrack,
      showInstructions,
      showHaukInstructions,
      haukDomain,
      haukServerUrl,
      haukPassword,
      sidebarTitle,
      ingressUrl,
      bodyTemplate,
      instructionsIngressUrl,
      instructionsPassword,
      profileUrl,
      copy,
      create,
      downloadKml,
      confirmClearHistory,
      confirmRegenerateTokens,
      confirmDelete,
      confirmUnsubscribe
    };
  }
};
</script>
