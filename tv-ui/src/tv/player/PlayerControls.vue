<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { normalizeRemoteKey } from '../input/remoteKeys';
import { useTvPlayerStore } from '../stores/player';

const player = useTvPlayerStore();
defineProps<{ lyricsVisible?: boolean }>();
const emit = defineEmits<{ openQueue: []; openQuality: []; openSoundEffect: []; openMore: []; scrubbing: [active: boolean] }>();
const preview = ref<number | null>(null);
const seeking = ref(false);
const seekMessage = ref('');
let seekIntent = 0;
const displayPosition = computed(() => preview.value ?? player.positionMs);
const progressPercent = computed(() => player.durationMs > 0 ? Math.min(100, displayPosition.value / player.durationMs * 100) : 0);
watch(() => preview.value !== null || seeking.value, active => emit('scrubbing', active));

function cancelPreview() { preview.value = null; seekMessage.value = ''; }
function previewSeek(delta: number) {
  if (seeking.value) return;
  if (player.durationMs <= 0) { seekMessage.value = '当前歌曲暂不支持进度跳转'; return; }
  preview.value = Math.max(0, Math.min(player.durationMs, (preview.value ?? player.positionMs) + delta));
  seekMessage.value = '';
}
async function commitSeek() {
  if (seeking.value || preview.value === null || !player.currentTrack) return;
  const intent = ++seekIntent;
  seeking.value = true;
  const position = preview.value;
  try {
    await player.seekTo(player.currentTrack.id, position);
    if (intent === seekIntent) { preview.value = null; seekMessage.value = '已跳转'; }
  } catch {
    if (intent === seekIntent) seekMessage.value = '跳转失败，按确认重试或返回取消';
  } finally { if (intent === seekIntent) seeking.value = false; }
}
function progressKey(event: KeyboardEvent) {
  const key = normalizeRemoteKey(event.key);
  if (key === 'ArrowLeft' || key === 'ArrowRight') {
    event.stopPropagation(); event.preventDefault();
    previewSeek(key === 'ArrowLeft' ? -10_000 : 10_000);
  } else if (key === 'GoBack' && preview.value !== null) {
    event.stopPropagation(); event.preventDefault(); cancelPreview();
  }
}
function abandonSeek() { seekIntent++; seeking.value = false; cancelPreview(); }
watch(() => player.currentTrack?.id, abandonSeek);
function onVisibility() { if (document.hidden) abandonSeek(); }
onMounted(() => document.addEventListener('visibilitychange', onVisibility));
onBeforeUnmount(() => { seekIntent++; document.removeEventListener('visibilitychange', onVisibility); });
const qualityLabel = computed(() => {
  const labels: Record<string, string> = {
    '128': '标准',
    '320': '高品',
    flac: '无损',
    hires: 'Hi-Res',
    high: '高品',
    lossless: '无损',
    standard: '标准',
  };
  return labels[player.quality.toLowerCase()] ?? (player.quality || '自动');
});
const pendingRepeatMode = ref<typeof player.repeatMode | null>(null);
let repeatRequestToken = 0;

function formatTime(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1_000));
  const minutes = Math.floor(totalSeconds / 60);
  return `${minutes}:${String(totalSeconds % 60).padStart(2, '0')}`;
}

function safely(command: () => Promise<void>) {
  void command().catch(() => undefined);
}

function cycleRepeat() {
  const current = pendingRepeatMode.value ?? player.repeatMode;
  const next = current === 'off' ? 'all' : current === 'all' ? 'one' : 'off';
  const requestToken = ++repeatRequestToken;
  pendingRepeatMode.value = next;
  void player.setRepeatMode(next).catch(() => {
    if (requestToken === repeatRequestToken) pendingRepeatMode.value = null;
  });
}

watch(() => player.repeatMode, (nativeMode) => {
  if (pendingRepeatMode.value === nativeMode) pendingRepeatMode.value = null;
});
</script>

<template>
  <section class="player-controls" aria-label="播放控制">
    <p v-if="player.pendingOperations || player.buffering || player.error || player.commandFailure" class="playback-status" role="status">{{ player.error?.message || (player.commandFailure ? '操作未完成，请重试；原播放状态已保留' : player.statusLabel) }}</p>
    <button
      v-tv-focus
      type="button"
      class="progress-control"
      data-focus-id="player-progress"
      :data-focus-up="lyricsVisible ? 'player-lyrics-panel' : 'mode-cover'"
      data-focus-down="player-toggle"
      :aria-label="`播放进度 ${formatTime(displayPosition)}，左右预览，确认跳转，返回取消`"
      :aria-busy="seeking"
      :data-seek-preview="preview !== null"
      @keydown="progressKey"
      @click="commitSeek"
      @blur="cancelPreview"
    >
      <span>{{ formatTime(displayPosition) }}</span>
      <i class="progress-track" role="progressbar" :aria-valuenow="Math.round(progressPercent)" aria-valuemin="0" aria-valuemax="100">
        <b data-progress-fill :style="{ width: `${progressPercent}%` }" />
      </i>
      <span>-{{ formatTime(Math.max(0, player.durationMs - displayPosition)) }}</span>
    </button>
    <p class="seek-hint" :role="seeking || preview !== null || seekMessage ? 'status' : undefined">{{ seeking ? '正在跳转…' : seekMessage || (preview !== null ? '左右调整 · 确认跳转 · 返回取消' : '选中进度条，左右预览播放位置') }}</p>
    <div class="control-row">
      <button v-tv-focus type="button" data-focus-id="player-previous" data-focus-up="player-progress" data-focus-left="player-more" data-focus-right="player-toggle" aria-label="上一首" @click="safely(player.previous)"><TvIcon name="previous" :size="32" /></button>
      <button v-tv-focus type="button" class="primary" data-focus-id="player-toggle" data-focus-up="player-progress" data-focus-left="player-previous" data-focus-right="player-next" :aria-label="player.isPlaying ? '暂停' : '播放'" @click="safely(player.toggle)"><TvIcon :name="player.isPlaying ? 'pause' : 'play'" :size="36" /></button>
      <button v-tv-focus type="button" data-focus-id="player-next" data-focus-up="player-progress" data-focus-left="player-toggle" data-focus-right="player-repeat" aria-label="下一首" @click="safely(player.next)"><TvIcon name="next" :size="32" /></button>
      <span class="control-divider" />
      <button v-tv-focus type="button" data-focus-id="player-repeat" data-focus-up="player-progress" data-focus-left="player-next" data-focus-right="player-quality" aria-label="切换循环方式" @click="cycleRepeat"><TvIcon :name="player.repeatMode === 'one' ? 'repeatOne' : player.repeatMode === 'all' ? 'repeat' : 'ordered'" :size="26" />{{ player.repeatMode === 'one' ? '单曲' : player.repeatMode === 'all' ? '列表' : '顺序' }}</button>
      <button v-tv-focus type="button" data-focus-id="player-quality" data-focus-up="player-progress" data-focus-left="player-repeat" data-focus-right="player-queue" aria-label="选择音质" @click="emit('openQuality')"><TvIcon name="quality" :size="26" />{{ qualityLabel }}</button>
      <button v-tv-focus type="button" data-focus-id="player-queue" data-focus-up="player-progress" data-focus-left="player-quality" data-focus-right="player-sound-effect" aria-label="打开播放队列" @click="emit('openQueue')"><TvIcon name="queue" :size="26" />队列 {{ player.queue.length }}</button>
      <button v-tv-focus type="button" data-focus-id="player-sound-effect" data-focus-up="player-progress" data-focus-left="player-queue" data-focus-right="player-more" aria-label="选择本地音效" @click="emit('openSoundEffect')"><TvIcon name="equalizer" :size="26" />音效</button>
      <button v-tv-focus type="button" data-focus-id="player-more" data-focus-up="player-progress" data-focus-left="player-sound-effect" data-focus-right="player-previous" aria-label="当前歌曲收藏与更多操作" @click="emit('openMore')"><TvIcon name="more" :size="26" />更多</button>
    </div>
  </section>
</template>

<style scoped>
.playback-status { margin:0; text-align:center; font-size:21px; line-height:1.4; color:var(--tv-text-secondary); }
.player-controls { display:flex; flex-direction:column; gap:14px; padding:16px 24px 18px; border:1px solid rgba(255,255,255,.14); border-radius:27px; background:rgba(31,29,34,.86); -webkit-backdrop-filter:blur(26px) saturate(135%); backdrop-filter:blur(26px) saturate(135%); box-shadow:0 22px 58px rgba(0,0,0,.36),inset 0 1px rgba(255,255,255,.11); }
.progress-control { width:100%; height:36px; padding:0 6px; display:grid; grid-template-columns:66px 1fr 74px; align-items:center; gap:14px; border:0; border-radius:12px; color:rgba(255,255,255,.64); background:transparent; font-variant-numeric:tabular-nums; font-size:20px; text-align:left; }
.progress-control span:last-child { text-align:right; }.progress-track { height:5px; overflow:hidden; border-radius:5px; background:rgba(255,255,255,.17); }.progress-track b { display:block; height:100%; border-radius:inherit; background:#eee7ed; transition:width 180ms linear; }
.control-row { display:flex; align-items:center; justify-content:center; gap:13px; }.control-row button { min-width:64px; height:60px; padding:0 20px; border:0; border-radius:18px; color:#fff; background:rgba(255,255,255,.085); font-size:22px; font-weight:680; }.control-row button:nth-child(-n+3) { width:56px; min-width:56px; padding:0; border-radius:50%; font-size:22px; }.control-row .primary { width:66px; min-width:66px; height:66px; color:#07111d; background:#fff; }.control-divider { width:1px; height:34px; margin:0 5px; background:rgba(255,255,255,.15); }
.player-controls { padding:18px 28px; border:0; background:rgba(28,25,31,.55); box-shadow:none; border-radius:28px; }
.control-row { gap:16px; }.control-row button { display:inline-flex; align-items:center; justify-content:center; gap:10px; white-space:nowrap; }.control-row button { background:transparent; color:#c1bac6; font-size:21px; }.control-row button:nth-child(-n+3) { width:68px; min-width:68px; height:68px; color:#fff; font-size:26px; }.control-row .primary { background:#f5f1f5; color:#201b23!important; width:76px!important; min-width:76px!important; height:76px!important; }
@media (prefers-reduced-motion:reduce) { .progress-track b { transition:none; } }
.progress-control { height:52px; }
.progress-control[data-seek-preview=true] .progress-track b { background:var(--tv-accent); transition:none; }
.seek-hint { min-height:26px; margin:-12px 0 -4px; text-align:center; font-size:20px; color:var(--tv-text-secondary); }
.progress-control:focus + .seek-hint { color:var(--tv-text); }
</style>
