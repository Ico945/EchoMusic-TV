<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import { useBackLayerRegistry } from '../input/backCoordinator';
import { nativeClient } from '../native/client';
import MvControls from '../player/MvControls.vue';
import { tvFocusRegistry } from '../focus/directive';

type TvMvDetail = {
  id: string;
  title: string;
  artist: string;
  quality: string;
  qualities: string[];
  sourceFocusId?: string;
};

const route = useRoute();
const router = useRouter();
const backLayers = useBackLayerRegistry();
const SAFE_ID = /^[A-Za-z0-9:_-]{1,128}$/;
const SAFE_FOCUS_ID = /^[A-Za-z0-9:_-]{1,160}$/;
const ALLOWED_QUALITIES = new Set(['fhd', 'hd', 'qhd', 'sd', 'ld']);
let releaseBackLayer: (() => void) | undefined;
let closeInFlight: Promise<boolean> | undefined;
let nativeClosed = false;
const page = ref<HTMLElement | null>(null);
const controlsVisible = ref(true);
const playback = ref({ playing: false, open: false, busy: false, error: false });
const canIdle = computed(() => playback.value.open && playback.value.playing && !playback.value.busy && !playback.value.error);
let idleTimer: ReturnType<typeof setTimeout> | undefined;
let lastFocusId = 'mv-progress';
let wakeConfirmHeld = false;
let disposed = false;
const WAKE_KEYS = new Set(['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', ' ']);
function clearIdle() { clearTimeout(idleTimer); idleTimer = undefined; }
function scheduleIdle() {
  clearIdle();
  if (!controlsVisible.value || !canIdle.value || document.hidden) return;
  idleTimer = setTimeout(() => {
    if (!canIdle.value || disposed) return;
    const active = document.activeElement as HTMLElement | null;
    if (active?.dataset.focusId && page.value?.contains(active)) lastFocusId = active.dataset.focusId;
    controlsVisible.value = false;
    page.value?.focus({ preventScroll: true });
  }, 4000);
}
function revealControls() {
  const hidden = !controlsVisible.value;
  controlsVisible.value = true;
  scheduleIdle();
  if (hidden) void nextTick(() => {
    if (disposed) return;
    const controls = Array.from(page.value?.querySelectorAll<HTMLElement>('[data-focus-id]:not([disabled])') ?? []);
    (controls.find(node => node.dataset.focusId === lastFocusId) ?? controls[0])?.focus({ preventScroll: true });
  });
}
function idleKey(event: KeyboardEvent) {
  if (wakeConfirmHeld && (event.key === 'Enter' || event.key === ' ')) {
    // Android deliberately drops keyup when waking moved focus away from the
    // original recipient. A fresh non-repeat down is a new press, not the hold.
    if (event.repeat) { event.preventDefault(); event.stopImmediatePropagation(); return; }
    wakeConfirmHeld = false;
  }
  if (!WAKE_KEYS.has(event.key) && !event.key.startsWith('Media')) return;
  if (!controlsVisible.value && WAKE_KEYS.has(event.key)) {
    wakeConfirmHeld = event.key === 'Enter' || event.key === ' ';
    event.preventDefault(); event.stopImmediatePropagation();
  }
  revealControls();
}
function idleKeyUp(event: KeyboardEvent) {
  if (wakeConfirmHeld && (event.key === 'Enter' || event.key === ' ')) {
    wakeConfirmHeld = false; event.preventDefault(); event.stopImmediatePropagation();
  }
}
function visibilityChanged() { wakeConfirmHeld = false; if (document.hidden) clearIdle(); else revealControls(); }
function cancelHeldInput() { wakeConfirmHeld = false; }
watch(canIdle, () => revealControls());

function queryString(name: string, fallback = ''): string {
  const value = route.query[name];
  return typeof value === 'string' ? value.slice(0, 160) : fallback;
}

const detail = computed<TvMvDetail>(() => {
  const routeId = String(route.params.id ?? '');
  const id = SAFE_ID.test(routeId) ? routeId : '';
  const requestedQuality = queryString('quality', 'hd');
  const quality = ALLOWED_QUALITIES.has(requestedQuality) ? requestedQuality : 'hd';
  const qualities = queryString('qualities', quality)
    .split(',')
    .filter((value) => ALLOWED_QUALITIES.has(value));
  if (!qualities.includes(quality)) qualities.unshift(quality);
  const focusId = queryString('sourceFocusId');
  return {
    id,
    title: queryString('title', 'MV 播放'),
    artist: queryString('artist', 'EchoMusic'),
    quality,
    qualities: [...new Set(qualities)],
    ...(SAFE_FOCUS_ID.test(focusId) ? { sourceFocusId: focusId } : {}),
  };
});

function closeNativeMv(): Promise<boolean> {
  if (nativeClosed) return Promise.resolve(true);
  if (closeInFlight) return closeInFlight;
  const pending = nativeClient.request<unknown>('video.close', {})
    .then((result) => {
      if (typeof result !== 'object' || result === null || Array.isArray(result)) return false;
      const record = result as Record<string, unknown>;
      if (Object.keys(record).length !== 1 || !('accepted' in record) || typeof record.accepted !== 'boolean') return false;
      nativeClosed = record.accepted;
      return nativeClosed;
    })
    .catch(() => false)
    .finally(() => { closeInFlight = undefined; });
  closeInFlight = pending;
  return pending;
}

async function closeMv(): Promise<void> {
  if (await closeNativeMv()) await router.back();
}

onBeforeRouteLeave(() => closeNativeMv());

onMounted(() => {
  window.addEventListener('keydown', idleKey, true);
  window.addEventListener('keyup', idleKeyUp, true);
  window.addEventListener('echo-tv-input-cancel', cancelHeldInput);
  window.addEventListener('echo-tv-resume', revealControls);
  document.addEventListener('visibilitychange', visibilityChanged);
  scheduleIdle();
  document.documentElement.classList.add('tv-mv-active');
  releaseBackLayer = backLayers.register(
    { kind: 'drawer', close: () => void closeMv() },
    detail.value.sourceFocusId,
  );
  void nextTick(() => {
    if (disposed) return;
    const firstControl = document.querySelector<HTMLElement>('.mv-detail-page [data-focus-id]:not([disabled])');
    if (!firstControl) return;
    tvFocusRegistry.register(firstControl);
    firstControl.focus({ preventScroll: true });
  });
});

onBeforeUnmount(() => {
  disposed = true;
  clearIdle();
  window.removeEventListener('keydown', idleKey, true);
  window.removeEventListener('keyup', idleKeyUp, true);
  window.removeEventListener('echo-tv-input-cancel', cancelHeldInput);
  window.removeEventListener('echo-tv-resume', revealControls);
  document.removeEventListener('visibilitychange', visibilityChanged);
  document.documentElement.classList.remove('tv-mv-active');
  releaseBackLayer?.();
  releaseBackLayer = undefined;
});
</script>

<template>
  <section ref="page" class="mv-detail-page" :class="{ 'is-idle': !controlsVisible }" data-page="mv-detail" :data-mv-idle="String(!controlsVisible)" tabindex="-1" @focusin="scheduleIdle">
    <header :aria-hidden="!controlsVisible">
      <span>ECHOMUSIC · MV</span>
      <h1>{{ detail.title }}</h1>
      <p>{{ detail.artist }}</p>
    </header>
    <div class="mv-control-layer" :aria-hidden="!controlsVisible" v-bind="controlsVisible ? {} : { inert: true }">
    <MvControls
      v-if="detail.id"
      :mv-id="detail.id"
      :initial-quality="detail.quality"
      :qualities="detail.qualities"
      @close="closeMv"
      @state="playback = $event"
    />
    <p v-else role="alert" class="invalid-mv">MV 信息无效</p>
    </div>
  </section>
</template>

<style scoped>
:global(html.tv-mv-active),:global(html.tv-mv-active body),:global(html.tv-mv-active #app),:global(html.tv-mv-active .tv-canvas) { background:transparent !important; background-image:none !important; }
.mv-detail-page { position:relative; width:100%; height:100%; display:flex; flex-direction:column; justify-content:space-between; align-items:center; padding:46px 52px 30px; background:linear-gradient(180deg,rgba(2,6,11,.54),transparent 34%,transparent 66%,rgba(2,6,11,.72)); }
header { align-self:flex-start; text-shadow:0 3px 18px rgba(0,0,0,.85); } header span { color:#86c5ff; font-size:13px; letter-spacing:2px; font-weight:800; } h1 { margin:8px 0 3px; font-size:42px; } header p { margin:0; color:rgba(255,255,255,.75); font-size:18px; }
.invalid-mv { margin:auto; padding:24px 34px; border-radius:18px; background:rgba(7,12,20,.82); }
.mv-detail-page { background:none; outline:none; }
.mv-detail-page::before { content:''; position:absolute; inset:0; pointer-events:none; background:linear-gradient(180deg,rgba(2,6,11,.54),transparent 34%,transparent 66%,rgba(2,6,11,.72)); transition:opacity 180ms ease; }
header,.mv-control-layer { position:relative; transition:opacity 180ms ease, visibility 0s; }
.mv-control-layer { width:100%; display:flex; justify-content:center; }
.is-idle::before,.is-idle header,.is-idle .mv-control-layer { opacity:0; }
.is-idle header,.is-idle .mv-control-layer { visibility:hidden; transition-delay:0s,180ms; pointer-events:none; }
</style>
