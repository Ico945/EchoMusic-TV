<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue';
import type { LyricLine } from '../stores/player';
import { shouldResumeFollow } from './lyricTimeline';
import { scrollWithin } from '../content/scrollVisibility';
import { cancelScrollMotion } from '../input/scrollMotion';
import { tvFocusRegistry } from '../focus/directive';

const props = defineProps<{ lines: LyricLine[]; activeIndex: number }>();
const selectedIndex = ref(props.activeIndex);
const manualAtMs = ref<number | null>(null);
const nowMs = ref(Date.now());
let followTimer: ReturnType<typeof setInterval> | undefined;
const panel = useTemplateRef<HTMLElement>('panel');
const visibleIndex = computed(() => (manualAtMs.value === null ? props.activeIndex : selectedIndex.value));

function scrollToVisible() {
  void nextTick(() => {
    const container = panel.value?.querySelector<HTMLElement>('.lyric-scroll');
    const line = container?.querySelector<HTMLElement>(`[data-lyric-index="${visibleIndex.value}"]`);
    if (container && line) scrollWithin(line, container, { block: 'center', animate: true, duration: 220 });
  });
}

function browse(delta: -1 | 1) {
  if (props.lines.length === 0) return;
  const base = visibleIndex.value >= 0 ? visibleIndex.value : 0;
  selectedIndex.value = Math.min(props.lines.length - 1, Math.max(0, base + delta));
  manualAtMs.value = Date.now();
  nowMs.value = manualAtMs.value;
  scrollToVisible();
}

watch(
  () => props.activeIndex,
  (index) => {
    if (manualAtMs.value === null) {
      selectedIndex.value = index;
      scrollToVisible();
    }
  },
);

watch(nowMs, (now) => {
  if (!shouldResumeFollow(manualAtMs.value, now)) return;
  manualAtMs.value = null;
  selectedIndex.value = props.activeIndex;
  scrollToVisible();
});

onMounted(() => {
  scrollToVisible();
  followTimer = setInterval(() => {
    if (manualAtMs.value !== null) nowMs.value = Date.now();
  }, 250);
});
onBeforeUnmount(() => {
  clearInterval(followTimer);
  const container = panel.value?.querySelector<HTMLElement>('.lyric-scroll');
  if (container) cancelScrollMotion(container);
});
</script>

<template>
  <section
    ref="panel"
    v-tv-focus
    class="lyric-panel"
    data-focus-id="player-lyrics-panel"
    data-focus-left="player-toggle"
    data-focus-right="mode-lyrics"
    tabindex="0"
    aria-label="同步歌词，上下键手动浏览"
    @keydown.up.stop.prevent="browse(-1)"
    @keydown.down.stop.prevent="browse(1)"
    @keydown.esc.stop.prevent="tvFocusRegistry.restore('player-toggle')"
  >
    <div v-if="lines.length" class="lyric-scroll">
      <p
        v-for="(line, index) in lines"
        :key="`${line.startMs}-${index}`"
        :data-lyric-index="index"
        :data-lyric-active="index === visibleIndex ? 'true' : undefined"
        :class="{ active: index === visibleIndex, near: Math.abs(index - visibleIndex) === 1 }"
      >
        <span class="lyric-text">{{ line.text || '· · ·' }}</span>
      </p>
    </div>
    <div v-else class="lyric-empty"><TvIcon name="music" :size="48" /><strong>暂无同步歌词</strong><small>音乐仍会继续播放</small></div>
    <span v-if="manualAtMs !== null" class="manual-hint">上下浏览 · 左键回到控制栏 · 3 秒后跟随</span>
  </section>
</template>

<style scoped>
.lyric-panel { position:relative; min-width:0; height:100%; overflow:hidden; border:1px solid rgba(255,255,255,.055); border-radius:34px; background:linear-gradient(145deg,rgba(15,23,35,.32),rgba(7,12,20,.1)); }
.lyric-scroll { height:100%; overflow-x:hidden; overflow-y:auto; padding:210px 38px; display:flex; flex-direction:column; gap:28px; scrollbar-width:none; overscroll-behavior:contain; scroll-behavior:smooth; }.lyric-scroll::-webkit-scrollbar { display:none; }.lyric-scroll p { margin:0; color:rgba(255,255,255,.24); font-size:30px; line-height:1.3; font-weight:650; letter-spacing:-.7px; transition:color 180ms,transform 180ms,opacity 180ms; transform-origin:left center; }.lyric-scroll p.near { color:rgba(255,255,255,.5); }.lyric-scroll p.active { color:#fff; font-size:42px; font-weight:770; transform:scale(1.018); text-shadow:0 10px 30px rgba(0,0,0,.36); }
.manual-hint { position:absolute; top:20px; right:24px; padding:9px 14px; border-radius:13px; color:rgba(255,255,255,.68); background:rgba(0,0,0,.3); font-size:14px; }
.lyric-empty { height:100%; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:10px; color:rgba(255,255,255,.58); }.lyric-empty span { font-size:62px; }.lyric-empty strong { color:#fff; font-size:28px; }.lyric-empty small { font-size:16px; }
.lyric-panel { --tv-focus-scale:1; border:0; background:none; border-radius:16px; }.lyric-scroll { padding:230px 24px; gap:28px; }
/* Reserve the same text geometry in both states. Only the glyph layer scales. */
.lyric-scroll p,.lyric-scroll p.active { font-size:calc(50px * var(--tv-lyric-scale,1)); line-height:1.2; font-weight:700; transform:none; text-shadow:none; }
.lyric-text { display:block; transform:scale(.76); transform-origin:left center; transition:transform 200ms cubic-bezier(.2,.7,.2,1); }
.active .lyric-text { transform:scale(1); }
@media (prefers-reduced-motion:reduce) { .lyric-scroll { scroll-behavior:auto; }.lyric-scroll p { transition:none; } }
</style>
