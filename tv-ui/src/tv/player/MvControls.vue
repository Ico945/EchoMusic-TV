<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { nativeClient } from '../native/client';
import { BACK_LAYER_REGISTRY_KEY } from '../input/backCoordinator';
import { tvFocusRegistry } from '../focus/directive';

const props = defineProps<{
  mvId: string;
  initialQuality: string;
  qualities: string[];
}>();
const emit = defineEmits<{ close: []; state: [value: { playing: boolean; open: boolean; busy: boolean; error: boolean }] }>();
const qualityMenu = ref(false);
const backLayers = inject(BACK_LAYER_REGISTRY_KEY, null);
let releaseQualityLayer: (() => void) | undefined;
const qualityLabel = (value: string) => ({ fhd: '超清', hd: '高清', qhd: '高清（QHD）', sd: '标准', ld: '流畅' } as Record<string, string>)[value] ?? value;
function closeQualityMenu() {
  qualityMenu.value = false;
  releaseQualityLayer?.(); releaseQualityLayer = undefined;
  void nextTick(() => tvFocusRegistry.restore('mv-quality-menu'));
}
function openQualityMenu() {
  qualityMenu.value = true;
  releaseQualityLayer?.();
  releaseQualityLayer = backLayers?.register({ kind: 'drawer', close: closeQualityMenu }, 'mv-quality-menu');
  void nextTick(() => tvFocusRegistry.restore(qualityBusy.value ? 'mv-quality-done' : `mv-quality-${snapshot.value.quality}`));
}

type VideoSnapshot = {
  durationMs: number;
  positionMs: number;
  playing: boolean;
  quality: string;
  errorCode: string | null;
  open: boolean;
  operation: 'open' | 'quality' | null;
  availableQualities?: string[];
};

type VideoCommandResult =
  | { accepted: true; quality: string; pending?: true }
  | { accepted: false; errorCode: string };

const ALLOWED_QUALITIES = new Set(['fhd', 'hd', 'qhd', 'sd', 'ld']);
const ALLOWED_ERROR_CODES = new Set([
  'MV_DESTROYED',
  'MV_COMMAND_FAILED',
  'MV_MUSIC_NOT_READY',
  'MV_NOT_OPEN',
  'MV_OPERATION_SUPERSEDED',
  'MV_PLAYBACK_FAILED',
  'MV_RESOLVE_FAILED',
  'MV_RESOLVER_BUSY',
  'MV_ROUTE_REQUIRED',
  'MV_UNAVAILABLE',
]);
const ROUTE_OBSERVATION_RETRY_DELAY_MS = 25;
const ROUTE_OBSERVATION_RETRY_LIMIT = 40;
const snapshot = ref<VideoSnapshot>({
  durationMs: 0,
  positionMs: 0,
  playing: false,
  quality: ALLOWED_QUALITIES.has(props.initialQuality) ? props.initialQuality : 'hd',
  errorCode: null,
  open: false,
  operation: null,
});
const availableQualities = computed(() => {
  const values = (snapshot.value.availableQualities ?? props.qualities).filter((quality) => ALLOWED_QUALITIES.has(quality));
  if (!values.includes(snapshot.value.quality)) values.unshift(snapshot.value.quality);
  return [...new Set(values)];
});
const progressPercent = computed(() => snapshot.value.durationMs > 0
  ? Math.min(100, (snapshot.value.positionMs / snapshot.value.durationMs) * 100)
  : 0);
const qualityBusy = computed(() => !snapshot.value.open || snapshot.value.operation !== null);
const errorMessage = computed(() => {
  if (snapshot.value.errorCode === 'MV_RESOLVER_BUSY') return 'MV 解析繁忙，请稍后重试';
  if (snapshot.value.errorCode === 'MV_RESOLVE_FAILED') return '当前 MV 暂时无法播放：视频源解析失败，请重试';
  if (snapshot.value.errorCode === 'MV_PLAYBACK_FAILED') return '当前 MV 暂时无法播放：视频加载或解码失败，请重试';
  return snapshot.value.errorCode ? '当前 MV 暂时无法播放' : '';
});
let pollTimer: ReturnType<typeof setTimeout> | undefined;
let disposed = false;

// Only semantic state changes affect idle chrome; 500ms position ticks must
// never restart its countdown.
watch([() => snapshot.value.playing, () => snapshot.value.open, () => snapshot.value.operation, () => snapshot.value.errorCode, qualityMenu], () => {
  emit('state', { playing: snapshot.value.playing, open: snapshot.value.open, busy: snapshot.value.operation !== null || qualityMenu.value, error: snapshot.value.errorCode !== null });
}, { immediate: true });

function exactRecord(value: unknown, allowed: readonly string[], required: readonly string[], label: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error(`${label} must be an object`);
  const record = value as Record<string, unknown>;
  const fields = Object.keys(record);
  if (fields.some((field) => !allowed.includes(field)) || required.some((field) => !(field in record))) {
    throw new Error(`${label} fields are invalid`);
  }
  return record;
}

function finiteMilliseconds(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error(`${label} is invalid`);
  return value;
}

function errorCode(value: unknown): string | null {
  if (value === null) return null;
  if (typeof value !== 'string' || !ALLOWED_ERROR_CODES.has(value)) throw new Error('Video error code is invalid');
  return value;
}

function parseSnapshot(value: unknown): VideoSnapshot {
  const fields = ['durationMs', 'positionMs', 'playing', 'quality', 'errorCode', 'open', 'operation'] as const;
  const record = exactRecord(value, [...fields, 'availableQualities'], fields, 'Video snapshot');
  let qualities: string[] | undefined;
  if ('availableQualities' in record) {
    if (!Array.isArray(record.availableQualities) || record.availableQualities.length > ALLOWED_QUALITIES.size
      || record.availableQualities.some((q) => typeof q !== 'string' || !ALLOWED_QUALITIES.has(q))) {
      throw new Error('Available video qualities are invalid');
    }
    qualities = [...new Set(record.availableQualities as string[])];
  }
  if (typeof record.playing !== 'boolean' || typeof record.open !== 'boolean') throw new Error('Video snapshot flags are invalid');
  const operation = record.operation;
  if (operation !== null && operation !== 'open' && operation !== 'quality') throw new Error('Video operation is invalid');
  if (typeof record.quality !== 'string' || (record.quality !== '' && !ALLOWED_QUALITIES.has(record.quality))) {
    throw new Error('Video quality is invalid');
  }
  return {
    durationMs: finiteMilliseconds(record.durationMs, 'Video duration'),
    positionMs: finiteMilliseconds(record.positionMs, 'Video position'),
    playing: record.playing,
    quality: record.quality || snapshot.value.quality,
    errorCode: errorCode(record.errorCode),
    open: record.open,
    operation,
    ...(qualities ? { availableQualities: qualities } : {}),
  };
}

function parseCommandResult(value: unknown, pendingAllowed: boolean): VideoCommandResult {
  const base = exactRecord(value, ['accepted', 'quality', 'pending', 'errorCode'], ['accepted'], 'Video command result');
  if (typeof base.accepted !== 'boolean') throw new Error('Video command acceptance is invalid');
  if (!base.accepted) {
    const rejected = exactRecord(value, ['accepted', 'errorCode'], ['accepted', 'errorCode'], 'Rejected video command');
    const code = errorCode(rejected.errorCode);
    if (code === null) throw new Error('Rejected video command code is missing');
    return { accepted: false, errorCode: code };
  }
  const accepted = exactRecord(
    value,
    pendingAllowed ? ['accepted', 'quality', 'pending'] : ['accepted', 'quality'],
    ['accepted', 'quality'],
    'Accepted video command',
  );
  if (typeof accepted.quality !== 'string' || !ALLOWED_QUALITIES.has(accepted.quality)) throw new Error('Accepted video quality is invalid');
  if ('pending' in accepted && accepted.pending !== true) throw new Error('Video pending state is invalid');
  return { accepted: true, quality: accepted.quality, ...('pending' in accepted ? { pending: true as const } : {}) };
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function openWhenRouteObserved(): Promise<VideoCommandResult> {
  for (let attempt = 0; ; attempt += 1) {
    const result = parseCommandResult(await nativeClient.request<unknown>('video.open', {
      mvId: props.mvId,
      quality: snapshot.value.quality,
    }), true);
    if (
      result.accepted
      || result.errorCode !== 'MV_ROUTE_REQUIRED'
      || attempt >= ROUTE_OBSERVATION_RETRY_LIMIT
      || disposed
    ) return result;
    await delay(ROUTE_OBSERVATION_RETRY_DELAY_MS);
  }
}

function formatTime(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1_000));
  return `${Math.floor(totalSeconds / 60)}:${String(totalSeconds % 60).padStart(2, '0')}`;
}

async function refreshSnapshot(): Promise<void> {
  try {
    const value = await nativeClient.request<unknown>('video.snapshot', {});
    if (!disposed) snapshot.value = parseSnapshot(value);
  } catch {
    if (!disposed) snapshot.value = { ...snapshot.value, errorCode: 'MV_UNAVAILABLE', playing: false };
  }
}

function scheduleSnapshotPoll(): void {
  if (disposed) return;
  pollTimer = setTimeout(async () => {
    await refreshSnapshot();
    scheduleSnapshotPoll();
  }, 500);
}

async function command(method: string, payload: object = {}): Promise<void> {
  try {
    const result = parseCommandResult(await nativeClient.request<unknown>(method, payload), method === 'video.setQuality');
    if (!result.accepted) {
      snapshot.value = { ...snapshot.value, errorCode: result.errorCode };
    } else if (result.pending) {
      snapshot.value = { ...snapshot.value, operation: 'quality' };
    }
  } catch {
    snapshot.value = { ...snapshot.value, errorCode: 'MV_COMMAND_FAILED' };
  }
}

function seek(deltaMs: -10_000 | 10_000): void {
  void command('video.seekBy', { deltaMs });
}

function setQuality(quality: string): void {
  if (qualityBusy.value || !ALLOWED_QUALITIES.has(quality) || quality === snapshot.value.quality) return;
  void command('video.setQuality', { quality });
}

let opening = false;
async function openMv(): Promise<void> {
  if (opening || disposed || snapshot.value.operation !== null) return;
  opening = true;
  snapshot.value = { ...snapshot.value, errorCode: null, operation: 'open' };
  try {
    const result = await openWhenRouteObserved();
    if (disposed) return;
    if (!result.accepted) {
      snapshot.value = { ...snapshot.value, errorCode: result.errorCode, operation: null };
    } else {
      if (result.pending) snapshot.value = { ...snapshot.value, open: false, operation: 'open' };
      await refreshSnapshot();
    }
  } catch {
    snapshot.value = { ...snapshot.value, errorCode: 'MV_UNAVAILABLE', operation: null };
  } finally {
    opening = false;
  }
}

onMounted(async () => {
  await openMv();
  scheduleSnapshotPoll();
});

onBeforeUnmount(() => {
  disposed = true;
  releaseQualityLayer?.();
  if (pollTimer !== undefined) clearTimeout(pollTimer);
});
</script>

<template>
  <section class="mv-controls" aria-label="MV 播放控制">
    <p v-if="errorMessage" role="alert">{{ errorMessage }}</p>
    <p v-else-if="snapshot.operation" role="status">{{ snapshot.operation === 'open' ? '正在加载 MV…' : '正在切换画质…' }}</p>
    <button
      v-tv-focus
      type="button"
      class="mv-progress"
      data-focus-id="mv-progress"
      data-focus-down="mv-toggle"
      aria-label="MV 播放进度，左右键快退或快进十秒"
      @keydown.left.stop.prevent="seek(-10_000)"
      @keydown.right.stop.prevent="seek(10_000)"
    >
      <span>{{ formatTime(snapshot.positionMs) }}</span>
      <i role="progressbar" :aria-valuenow="Math.round(progressPercent)" aria-valuemin="0" aria-valuemax="100">
        <b :style="{ width: `${progressPercent}%` }" />
      </i>
      <span>{{ formatTime(snapshot.durationMs) }}</span>
    </button>
    <div class="mv-actions">
      <button v-tv-focus type="button" data-focus-id="mv-toggle" data-focus-up="mv-progress" :aria-label="!snapshot.open ? '重试播放 MV' : snapshot.playing ? '暂停 MV' : '播放 MV'" @click="snapshot.open ? command('video.toggle') : openMv()">
        <TvIcon :name="errorMessage && !snapshot.open ? 'refresh' : snapshot.playing ? 'pause' : 'play'" :size="30" />
      </button>
      <button v-tv-focus type="button" data-focus-id="mv-quality-menu" data-focus-up="mv-progress" data-focus-left="mv-toggle" data-focus-right="mv-close" :aria-expanded="qualityMenu" @click="openQualityMenu"><TvIcon name="quality" />画质 · {{ qualityLabel(snapshot.quality) }}</button>
      <button v-tv-focus type="button" data-focus-id="mv-close" data-focus-up="mv-progress" data-focus-left="mv-quality-menu" @click="emit('close')"><TvIcon name="back" />返回详情</button>
    </div>
    <section v-show="qualityMenu" class="mv-quality-panel" role="dialog" aria-modal="true" aria-label="MV 画质">
      <h2>画质</h2><p>仅显示当前视频可用的清晰度</p>
      <button
        v-for="quality in availableQualities"
        :key="quality"
        v-tv-focus
        type="button"
        :class="{ active: quality === snapshot.quality }"
        :data-focus-id="`mv-quality-${quality}`"
        :disabled="qualityBusy"
        @click="setQuality(quality)"
      ><TvIcon name="quality" />{{ qualityLabel(quality) }}{{ quality === snapshot.quality ? ' · 当前' : '' }}</button>
      <button v-tv-focus type="button" data-focus-id="mv-quality-done" @click="closeQualityMenu">完成</button>
    </section>
  </section>
</template>

<style scoped>
.mv-controls { position:relative; }
.mv-quality-panel { position:absolute; right:0; bottom:100%; margin-bottom:20px; width:380px; padding:24px; border:1px solid var(--tv-border); border-radius:24px; background:#27232a; box-shadow:0 20px 50px #0008; }
.mv-quality-panel h2 { margin:0 0 12px; font-size:28px; }.mv-quality-panel p { font-size:20px; line-height:1.4; color:var(--tv-text-secondary); text-align:left; }
.mv-quality-panel button { display:flex; align-items:center; gap:12px; width:100%; min-height:56px; margin-top:10px; border:0; border-radius:14px; background:#ffffff0d; color:var(--tv-text); font-size:23px; padding:8px 16px; }
.mv-quality-panel button.active { background:var(--tv-accent); color:#fff; }
.mv-controls { width:min(1100px,90%); display:flex; flex-direction:column; gap:14px; padding:18px 24px; border:1px solid rgba(255,255,255,.18); border-radius:24px; background:rgba(7,12,20,.58); backdrop-filter:blur(22px); box-shadow:0 22px 60px rgba(0,0,0,.42); }
.mv-controls p { margin:0; color:#ffb8b8; text-align:center; }
.mv-progress { width:100%; height:38px; padding:0 8px; display:grid; grid-template-columns:60px 1fr 60px; align-items:center; gap:14px; border:0; border-radius:12px; color:#fff; background:transparent; font-variant-numeric:tabular-nums; }
.mv-progress i { height:6px; overflow:hidden; border-radius:6px; background:rgba(255,255,255,.25); }.mv-progress b { display:block; height:100%; border-radius:inherit; background:#fff; }
.mv-actions { display:flex; justify-content:center; align-items:center; gap:12px; }.mv-actions button { min-width:78px; height:48px; padding:0 18px; border:1px solid rgba(255,255,255,.14); border-radius:16px; color:#fff; background:rgba(255,255,255,.09); font-weight:700; }.mv-actions button:first-child { min-width:58px; width:58px; height:58px; border-radius:50%; color:#07111d; background:#fff; font-size:20px; }.mv-actions button.active { border-color:#7cbcff; background:rgba(55,139,236,.45); }
.mv-actions button { display:inline-flex; align-items:center; justify-content:center; gap:10px; padding:0 20px; }
</style>
