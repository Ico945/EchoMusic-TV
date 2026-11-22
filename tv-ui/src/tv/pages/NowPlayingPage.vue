<script setup lang="ts">
import { useTvSettingsStore } from '../stores/settings';
import TvIcon from '../components/TvIcon.vue';
import TvOverlayTransition from '../components/TvOverlayTransition.vue';
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import LyricModeSwitch, { type PlayerDisplayMode } from '../player/LyricModeSwitch.vue';
import LyricPanel from '../player/LyricPanel.vue';
import PlayerControls from '../player/PlayerControls.vue';
import PlayerHero from '../player/PlayerHero.vue';
import QueueDrawer from '../player/QueueDrawer.vue';
import QualityDrawer from '../player/QualityDrawer.vue';
import PlaybackErrorDialog from '../player/PlaybackErrorDialog.vue';
import SoundEffectDrawer from '../player/SoundEffectDrawer.vue';
import { tvFocusRegistry } from '../focus/directive';
import { BACK_LAYER_REGISTRY_KEY, type BackLayerKind } from '../input/backCoordinator';
import { activeLyricIndex } from '../player/lyricTimeline';
import { useTvPlayerStore } from '../stores/player';
import SongActions from '../content/SongActions.vue';
import type { TvTrack } from '../stores/app';

const player = useTvPlayerStore();
const songMenuOpen = ref(false);
const menuTrack = computed<TvTrack | null>(() => player.currentTrack ? {
  id: player.currentTrack.id, title: player.currentTrack.title, artist: player.currentTrack.artists.join('、'),
  album: player.currentTrack.album, durationMs: player.currentTrack.durationMs, artwork: player.currentTrack.artworkUrl ?? '',
} : null);
const settings = useTvSettingsStore();
const router = useRouter();
const route = useRoute();
const page = useTemplateRef<HTMLElement>('page');
const mode = ref<PlayerDisplayMode>('lyrics');
const presentation = computed<PlayerDisplayMode>(() => mode.value === 'lyrics' && player.lyrics.length ? 'lyrics' : 'cover');
const drawer = ref<'queue' | 'quality' | 'sound-effect' | null>(null);
const pendingQuality = ref<string | null>(null);
const qualityMessage = ref('');
let qualityIntent = 0;
const controlsVisible = ref(true);
const scrubbing = ref(false);
const queueSelecting = ref(false);
const backLayers = inject(BACK_LAYER_REGISTRY_KEY, null);
const replayableErrorCodes = new Set(['PLAYBACK_RESOLVE_FAILED', 'PLAYBACK_NEXT_FAILED', 'PLAYBACK_PREVIOUS_FAILED']);
const playbackErrorOpen = computed(() => player.error !== null && replayableErrorCodes.has(player.error.code));
const overlayOpen = computed(() => drawer.value !== null || playbackErrorOpen.value || songMenuOpen.value);
const canIdle = computed(() => Boolean(player.currentTrack) && player.isPlaying && !player.error && !overlayOpen.value && !scrubbing.value);
watch(canIdle, () => scheduleIdle());
let releaseOverlayLayer: (() => void) | undefined;
let overlayRestoreFocusId: string | undefined;
let overlayKind: BackLayerKind | undefined;
let idleTimer: ReturnType<typeof setTimeout> | undefined;
let lastPlayerFocusId = 'player-toggle';
const PLAYER_IDLE_DELAY_MS = 10_000;
const wakeKeys = new Set([
  'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', ' ',
  'MediaPlayPause', 'MediaTrackPrevious', 'MediaTrackNext',
]);
const wakeOnlyKeys = new Set(['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', ' ']);
const lyricIndex = computed(() => activeLyricIndex(player.lyrics, player.positionMs));
const backgroundStyle = computed(() => (player.currentTrack?.artworkUrl ? { '--player-artwork': `url("${player.currentTrack.artworkUrl}")` } : {}));

function currentFocusId(fallback: string): string {
  return (document.activeElement as HTMLElement | null)?.dataset.focusId ?? fallback;
}

function restoreFocus(id: string) {
  void nextTick(() => {
    const target = document.querySelector<HTMLElement>(`[data-focus-id="${id}"]`);
    if (target && !target.closest('[inert]')) {
      target.focus({ preventScroll: true });
      return;
    }
    tvFocusRegistry.restore(id);
  });
}

function clearIdleTimer() {
  if (idleTimer !== undefined) clearTimeout(idleTimer);
  idleTimer = undefined;
}

function rememberPlayerFocus(event: FocusEvent) {
  const target = event.target as HTMLElement | null;
  if (target?.dataset.focusId && !target.closest('.overlay')) lastPlayerFocusId = target.dataset.focusId;
  scheduleIdle();
}

function enterIdle() {
  idleTimer = undefined;
  if (!canIdle.value) return;
  const active = document.activeElement as HTMLElement | null;
  if (active?.dataset.focusId && active.closest('[data-page="now-playing"]') && !active.closest('.overlay')) {
    lastPlayerFocusId = active.dataset.focusId;
  }
  controlsVisible.value = false;
  void nextTick(() => page.value?.focus({ preventScroll: true }));
}

function scheduleIdle() {
  clearIdleTimer();
  if (!controlsVisible.value || !canIdle.value) return;
  idleTimer = setTimeout(enterIdle, PLAYER_IDLE_DELAY_MS);
}

function revealControls(restore = true) {
  const wasHidden = !controlsVisible.value;
  controlsVisible.value = true;
  scheduleIdle();
  if (restore && wasHidden) restoreFocus(lastPlayerFocusId);
}

function handleIdleKey(event: KeyboardEvent) {
  if (!wakeKeys.has(event.key)) return;
  if (controlsVisible.value) {
    scheduleIdle();
    return;
  }

  const consumeWake = wakeOnlyKeys.has(event.key);
  if (consumeWake) {
    event.preventDefault();
    event.stopImmediatePropagation();
  }
  if (!event.repeat && (event.key === 'Enter' || event.key === ' ')) {
    lastPlayerFocusId = 'player-toggle';
    safely(player.toggle);
  }
  // D-pad wakes controls; dedicated media keys retain their playback meaning.
  revealControls();
}

function registerOverlay(kind: BackLayerKind, restoreFocusId: string, initialFocusId: string) {
  releaseOverlayLayer?.();
  overlayKind = kind;
  overlayRestoreFocusId = restoreFocusId;
  releaseOverlayLayer = backLayers?.register({ kind, close: closeOverlay }, restoreFocusId);
  void nextTick(() => restoreFocus(initialFocusId));
}

function openDrawer(next: NonNullable<typeof drawer.value>) {
  drawer.value = next;
  const trigger = currentFocusId(next === 'queue' ? 'player-queue' : next === 'quality' ? 'player-quality' : 'player-sound-effect');
  const current = player.queue[player.queueIndex] ?? player.queue[0];
  registerOverlay('drawer', trigger, next === 'queue' ? current ? `queue-track-${current.id}` : 'queue-close' : next === 'quality' ? player.currentTrack?.qualities.includes(player.quality) ? `quality-${player.quality}` : player.currentTrack?.qualities[0] ? `quality-${player.currentTrack.qualities[0]}` : 'quality-close' : 'sound-effect-off');
}

function finishClosingOverlay() {
  const restore = overlayRestoreFocusId;
  overlayRestoreFocusId = undefined;
  overlayKind = undefined;
  releaseOverlayLayer?.();
  releaseOverlayLayer = undefined;
  if (restore) restoreFocus(restore);
}

function closeOverlay() {
  if (playbackErrorOpen.value) {
    safely(async () => {
      await player.clearError();
      finishClosingOverlay();
    });
    return;
  }
  drawer.value = null;
  finishClosingOverlay();
}

function closeDrawer() {
  if (drawer.value === null) return;
  closeOverlay();
}

function openResolveDialog() {
  if (overlayKind === 'dialog') return;
  drawer.value = null;
  registerOverlay('dialog', currentFocusId('player-toggle'), 'playback-error-retry');
}

watch(playbackErrorOpen, (open) => {
  if (open) songMenuOpen.value = false;
  if (open || overlayKind !== 'dialog') return;
  overlayKind = undefined;
  releaseOverlayLayer?.();
  releaseOverlayLayer = undefined;
});

watch(overlayOpen, (open) => {
  if (open) {
    clearIdleTimer();
    controlsVisible.value = true;
  } else {
    scheduleIdle();
  }
});

// Native position updates carry a freshly parsed track object every 500ms.
// Only a real track identity change is a reason to reveal/reset the chrome.
watch(() => player.currentTrack?.id, () => {
  qualityIntent++;
  pendingQuality.value = null;
  qualityMessage.value = '';
  const wasHidden = !controlsVisible.value;
  controlsVisible.value = true;
  scheduleIdle();
  if (wasHidden && !overlayOpen.value) restoreFocus(player.currentTrack ? 'player-toggle' : 'player-empty-home');
});

watch(() => [player.isPlaying, player.error?.errorId], () => revealControls(!overlayOpen.value));

watch(mode, () => scheduleIdle());

onMounted(() => {
  window.addEventListener('keydown', handleIdleKey, true);
  scheduleIdle();
  if (route.query.panel === 'queue' && player.currentTrack) void nextTick(() => openDrawer('queue'));
});

onBeforeUnmount(() => {
  qualityIntent++;
  clearIdleTimer();
  window.removeEventListener('keydown', handleIdleKey, true);
  releaseOverlayLayer?.();
});

function safely(command: () => Promise<void>) {
  void command().catch(() => undefined);
}

async function selectQueueItem(trackId: string) {
  if (queueSelecting.value) return;
  queueSelecting.value = true;
  try { await player.selectQueueItem(trackId); closeDrawer(); } catch { /* retain queue for retry */ }
  finally { queueSelecting.value = false; }
}

async function selectQuality(quality: string) {
  if (pendingQuality.value || quality === player.quality) return;
  const intent = ++qualityIntent;
  pendingQuality.value = quality;
  qualityMessage.value = '';
  try {
    await player.setQuality(quality);
    if (intent !== qualityIntent) return;
    // Snapshot events are authoritative; never mark a requested quality as active.
    qualityMessage.value = '已处理切换请求，当前音质以播放状态为准；资源不可用时可能回退。';
  } catch {
    if (intent === qualityIntent) qualityMessage.value = '音质切换失败，请按确认重试。';
  } finally {
    if (intent === qualityIntent) pendingQuality.value = null;
  }
}

async function recover(action: 'retry' | 'skip' | 'close') {
  const errorId = player.error?.errorId;
  const code = player.error?.code;
  if (action === 'retry' && code === 'PLAYBACK_RESOLVE_FAILED') await player.retryCurrent();
  if (action === 'retry' && code === 'PLAYBACK_NEXT_FAILED') await player.next();
  if (action === 'retry' && code === 'PLAYBACK_PREVIOUS_FAILED') await player.previous();
  if (action === 'skip') await player.next();
  if (player.error?.errorId === errorId) await player.clearError();
  if (!playbackErrorOpen.value) finishClosingOverlay();
}
</script>

<template>
  <section
    ref="page"
    class="now-playing-page"
    :class="{ 'is-idle': !controlsVisible }"
    data-page="now-playing"
    :data-background="settings.settings.lyricBackground"
    :data-mode="presentation"
    :data-player-idle="String(!controlsVisible)"
    :style="backgroundStyle"
    tabindex="-1"
    @focusin="rememberPlayerFocus"
  >
    <template v-if="player.currentTrack">
      <div class="ambient ambient-one" aria-hidden="true" />
      <div class="ambient ambient-two" aria-hidden="true" />
      <div class="now-playing-content" v-bind="overlayOpen ? { inert: true } : {}">
      <header class="player-header" :aria-hidden="!controlsVisible" v-bind="!controlsVisible ? { inert: true } : {}">
        <span>ECHOMUSIC TV</span>
        <LyricModeSwitch v-model="mode" :lyrics-available="player.lyrics.length > 0" :lyrics-visible="presentation === 'lyrics'" />
      </header>
      <div class="player-stage">
        <PlayerHero :track="player.currentTrack" :mode="presentation" :quality="player.quality" :immersive="!controlsVisible" />
        <LyricPanel v-if="presentation === 'lyrics'" :lines="player.lyrics" :active-index="lyricIndex" />

      </div>
      <PlayerControls
        class="bottom-controls"
        :lyrics-visible="presentation === 'lyrics'"
        :aria-hidden="!controlsVisible"
        v-bind="!controlsVisible ? { inert: true } : {}"
        @open-queue="openDrawer('queue')"
        @open-quality="openDrawer('quality')"
        @open-sound-effect="openDrawer('sound-effect')"
        @open-more="songMenuOpen = true"
        @scrubbing="scrubbing = $event"
      />
      </div>
      <TvOverlayTransition><div v-if="drawer && !playbackErrorOpen" :key="drawer" class="overlay" :class="{ 'queue-overlay': drawer === 'queue' }"><QueueDrawer v-if="drawer === 'queue'" :queue="player.queue" :queue-index="player.queueIndex" :selecting="queueSelecting" @close="closeDrawer" @select="selectQueueItem" /><QualityDrawer v-else-if="drawer === 'quality'" :qualities="player.currentTrack.qualities" :selected="player.quality" :pending="pendingQuality" :message="qualityMessage" @close="closeDrawer" @select="selectQuality" /><SoundEffectDrawer v-else @close="closeDrawer" /></div></TvOverlayTransition>
      <TvOverlayTransition><div v-if="playbackErrorOpen" class="overlay"><PlaybackErrorDialog :error="player.error!" @opened="openResolveDialog" @action="(action) => safely(() => recover(action))" /></div></TvOverlayTransition>
      <SongActions v-if="songMenuOpen" :track="menuTrack" source-focus-id="player-more" @close="songMenuOpen = false" />
    </template>
    <div v-else class="player-empty" data-player-empty>
      <span aria-hidden="true"><TvIcon name="music" :size="64" /></span>
      <h1>还没有正在播放的歌曲</h1>
      <p>从首页、搜索或资料库选择一首歌开始播放。</p>
      <button v-tv-focus type="button" data-focus-id="player-empty-home" @click="router.push({ name: 'home' })">返回首页</button>
    </div>
  </section>
</template>

<style scoped>
.now-playing-page { --player-artwork:none; position:relative; height:100%; min-height:0; overflow:hidden; padding:0; isolation:isolate; outline:none; }
.now-playing-page::before { content:""; position:absolute; inset:-18%; z-index:-3; background-image:var(--player-artwork); background-position:center; background-size:cover; filter:blur(96px) saturate(1.3); opacity:.22; transform:scale(1.12); transition:opacity 500ms ease,transform 800ms ease; }
.now-playing-page::after { content:""; position:absolute; inset:0; z-index:-2; background:linear-gradient(112deg,rgba(5,9,15,.91),rgba(10,17,28,.62) 47%,rgba(4,8,14,.9)); }
.ambient { position:absolute; z-index:-1; width:560px; height:560px; border-radius:50%; filter:blur(110px); opacity:.18; animation:drift 14s ease-in-out infinite alternate; }
.ambient-one { top:-260px; right:2%; background:#218eff; }
.ambient-two { bottom:-330px; left:5%; background:#9a55e9; animation-delay:-5s; }
.now-playing-content { position:relative; height:100%; }
.player-header { position:absolute; top:0; left:8px; right:8px; z-index:3; height:72px; display:flex; align-items:center; justify-content:space-between; transition:opacity 220ms ease,transform 220ms ease; }
.player-header>span { color:rgba(255,255,255,.52); font-size:14px; letter-spacing:2.4px; font-weight:760; }
.player-stage { height:100%; min-height:0; display:grid; transition:transform 240ms cubic-bezier(.2,.7,.2,1); }
.mode-space { height:100%; display:flex; flex-direction:column; align-items:center; justify-content:center; text-align:center; color:rgba(255,255,255,.5); }
.mode-space span { color:#82c0ff; font-size:14px; letter-spacing:2px; font-weight:720; }
.mode-space h2 { margin:12px 0 8px; max-width:620px; color:#fff; font-size:44px; }
.mode-space p { margin:0; font-size:18px; }
.bottom-controls { position:absolute; left:8px; right:8px; bottom:10px; z-index:3; transition:opacity 220ms ease,transform 220ms ease; }
.is-idle .player-header { opacity:0; transform:translateY(-18px); pointer-events:none; }
.is-idle .bottom-controls { opacity:0; transform:translateY(28px); pointer-events:none; }
.is-idle .player-stage { transform:translateY(44px); }
.is-idle::before { opacity:.3; transform:scale(1.16); }
.overlay { position:absolute; inset:0; z-index:8; display:grid; place-items:center; background:rgba(3,7,12,.76); backdrop-filter:blur(10px); }
.player-empty { height:100%; display:flex; flex-direction:column; align-items:center; justify-content:center; text-align:center; }
.player-empty>span { font-size:78px; color:rgba(255,255,255,.72); }
.player-empty h1 { margin:16px 0 8px; font-size:46px; }
.player-empty p { margin:0; color:rgba(255,255,255,.55); font-size:19px; }
.player-empty button { min-width:180px; height:56px; margin-top:30px; border:0; border-radius:28px; color:#07111d; background:#fff; font-size:17px; font-weight:760; }
.now-playing-page::after { background:linear-gradient(105deg,rgba(17,14,21,.78),rgba(13,12,18,.5) 55%,rgba(14,12,18,.7)); }
.now-playing-page::before { opacity:.4; }.ambient-one { background:#a26073; }.ambient-two { background:#6c6689; }
.player-header { top:36px; left:96px; right:96px; }.player-stage { padding:132px 96px 236px; grid-template-columns:40fr 60fr; gap:76px; }
.bottom-controls { left:320px; right:320px; bottom:54px; }
[data-mode="cover"] .player-stage,[data-mode="cover"].is-idle .player-stage { display:flex; align-items:center; justify-content:center; }
[data-mode="cover"] .player-stage { padding-top:112px; padding-bottom:222px; }
.queue-overlay { background:linear-gradient(90deg,transparent 20%,rgba(8,7,10,.3) 55%,rgba(8,7,10,.7)); backdrop-filter:none; place-items:stretch; }
.queue-overlay :deep(.player-drawer) { position:absolute; top:54px; bottom:54px; right:64px; width:640px; max-height:none; height:auto; }
@keyframes drift { to { transform:translate3d(42px,22px,0) scale(1.08); } }
@media (prefers-reduced-motion:reduce) {
  .ambient { animation:none; }
  .now-playing-page::before { transform:none; transition:none; }
  .player-header,.player-stage,.bottom-controls { transition:none; }
}
.now-playing-page[data-background=gradient]::before { background-image:linear-gradient(130deg,#5f364f,#252b4c 52%,#1f1930); filter:none; opacity:.55; }
</style>
