<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, onBeforeUnmount, onMounted, watch } from 'vue';
import { ConfirmHoldDetector } from '../input/confirmHold';
import type { TvTrack } from '../stores/app';
import { useTvPlayerStore } from '../stores/player';
const player = useTvPlayerStore();
// Native progress snapshots recreate currentTrack every 500ms. Depend on the
// stable scalar identity so progress alone never patches every row/directive.
const currentTrackId = computed(() => player.currentTrack?.id);

const props = withDefaults(defineProps<{
  tracks: TvTrack[];
  group: string;
  emptyText?: string;
  upFocusId?: string;
  downFocusId?: string;
  leftFocusId?: string;
  menuEnabled?: boolean;
}>(), { menuEnabled: true });

const emit = defineEmits<{
  play: [track: TvTrack, index: number];
  menu: [track: TvTrack];
}>();

const detectors = new Map<string, ConfirmHoldDetector>();

function indexOf(track: TvTrack): number {
  return props.tracks.findIndex((item) => item.id === track.id);
}

function detectorFor(track: TvTrack): ConfirmHoldDetector {
  let detector = detectors.get(track.id);
  if (!detector) {
    detector = new ConfirmHoldDetector(
      () => emit('play', track, indexOf(track)),
      () => { if (props.menuEnabled !== false) emit('menu', track); },
    );
    detectors.set(track.id, detector);
  }
  return detector;
}

function keyDown(track: TvTrack, event: KeyboardEvent): void {
  if (event.key !== 'Enter') return;
  event.preventDefault();
  event.stopPropagation();
  detectorFor(track).keyDown(event.repeat);
}

function keyUp(track: TvTrack, event: KeyboardEvent): void {
  if (event.key !== 'Enter') return;
  event.preventDefault();
  event.stopPropagation();
  detectorFor(track).keyUp();
}

function cancelAll(): void {
  detectors.forEach((detector) => detector.cancel());
  detectors.clear();
}

function visibilityChanged(): void {
  if (document.hidden) cancelAll();
}

// Removed rows do not receive blur reliably; dispose their pending actions too.
watch(() => props.tracks.map((track) => track.id), (ids) => {
  const live = new Set(ids);
  detectors.forEach((detector, id) => {
    if (!live.has(id)) {
      detector.cancel();
      detectors.delete(id);
    }
  });
});

onMounted(() => {
  window.addEventListener('blur', cancelAll);
  window.addEventListener('echo-tv-input-cancel', cancelAll);
  document.addEventListener('visibilitychange', visibilityChanged);
});

onBeforeUnmount(() => {
  cancelAll();
  window.removeEventListener('blur', cancelAll);
  window.removeEventListener('echo-tv-input-cancel', cancelAll);
  document.removeEventListener('visibilitychange', visibilityChanged);
});
</script>

<template>
  <section class="song-list" :aria-label="group">
    <div v-if="tracks.length" class="rows">
      <div
        v-for="(track, index) in tracks"
        :key="track.id"
        v-tv-focus
        class="song-row"
        :aria-current="currentTrackId === track.id ? 'true' : undefined"
        role="button"
        tabindex="0"
        :data-focus-id="`${group}-${track.id}`"
        :data-focus-group="group"
        :data-focus-boundary-up="index === 0 || undefined"
        :data-focus-boundary-down="index === tracks.length - 1 || undefined"
        :data-focus-up="index > 0 ? `${group}-${tracks[index - 1].id}` : upFocusId"
        :data-focus-down="index + 1 < tracks.length ? `${group}-${tracks[index + 1].id}` : downFocusId"
        :data-focus-left="leftFocusId"
        :data-focus-right="`${group}-${track.id}`"
        @keydown.enter="keyDown(track, $event)"
        @keyup.enter="keyUp(track, $event)"
        @blur="detectors.get(track.id)?.cancel()"
      >
        <span class="row-index" aria-hidden="true"><TvIcon v-if="currentTrackId === track.id" :name="player.isPlaying ? 'music' : 'pause'" :size="23" /><template v-else>{{ index + 1 }}</template></span>
        <span class="row-art" :class="`tone-${(index % 5) + 1}`">
          <img v-if="track.artwork" :src="track.artwork" alt="" loading="lazy" />
          <TvIcon v-else name="music" :size="26" />
        </span>
        <span class="row-copy">
          <strong>{{ track.title }}</strong>
          <small>{{ track.artist }}<template v-if="track.album"> · {{ track.album }}</template></small>
        </span>
        <span v-if="menuEnabled !== false" class="row-hint" aria-hidden="true">长按更多</span>
      </div>
    </div>
    <p v-else class="empty">{{ emptyText ?? '暂无歌曲' }}</p>
  </section>
</template>

<style scoped>
.song-list { padding: 4px 0; }
.rows { display: flex; flex-direction: column; gap: 10px; padding:12px; }
.song-row { --tv-focus-scale:1; }
.song-row { display: flex; align-items: center; gap: 16px; min-height: 90px; padding: 8px 14px; border: 1px solid transparent; border-radius: var(--tv-radius-card); color: var(--tv-text); background: rgba(255, 255, 255, 0.03); cursor: pointer; outline: none; }
.song-row:focus, .song-row:focus-within { border-color: var(--tv-border); background: rgba(255, 255, 255, 0.14); }
.row-index { width: 30px; color: var(--tv-text-secondary); font-size: 15px; text-align: center; }
.row-art { width: 58px; height: 58px; flex: none; display: grid; place-items: center; border-radius: 12px; overflow: hidden; }
.row-art img { width: 100%; height: 100%; object-fit: cover; }
.row-art i { font-size: 24px; font-style: normal; }
.row-copy { min-width: 0; flex: 1; }
.row-copy strong, .row-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-copy strong { font-size: 23px; }
.row-copy small { margin-top: 3px; color: var(--tv-text-secondary); font-size: 20px; }
.row-hint { flex: none; padding: 6px 12px; border: 1px solid var(--tv-border); border-radius: 10px; color: var(--tv-text); font-size: 18px; background: var(--tv-glass); opacity: 0; transition: opacity 120ms; }
.song-row:focus .row-hint, .song-row:focus-within .row-hint { opacity: 1; }
.tone-1 { background: linear-gradient(135deg, #236fa5, #75c8d2); }
.tone-2 { background: linear-gradient(135deg, #d65d7d, #f4b67d); }
.tone-3 { background: linear-gradient(135deg, #6546a9, #cf78c2); }
.tone-4 { background: linear-gradient(135deg, #1f4d60, #67b3a3); }
.tone-5 { background: linear-gradient(135deg, #a94d2d, #f0a248); }
.empty { padding: 26px 8px; color: var(--tv-text-secondary); font-size: 16px; }
.song-row[aria-current=true] .row-index,.song-row[aria-current=true] .row-copy strong { color:var(--tv-accent); }
</style>
