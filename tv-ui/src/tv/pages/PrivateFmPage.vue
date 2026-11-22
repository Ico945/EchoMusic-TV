<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useTransientNotice } from '../content/notice';
import { recallFocus, rememberFocus, mayAutoRestorePageFocus } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import type { TvTrack } from '../stores/app';
import { useTvBrowseStore } from '../stores/browse';
import { detailContentKey, useTvDetailStore } from '../stores/detail';
import { useTvPlayerStore } from '../stores/player';

const PAGE_KEY = 'fm';
let pageActive = true;
const browse = useTvBrowseStore();
const player = useTvPlayerStore();
const detail = useTvDetailStore();
const { notice, show } = useTransientNotice();
const busy = ref(false);
const currentIndex = computed(() => Math.max(0, browse.fmTracks.findIndex(track => track.id === player.currentTrack?.id)));
const current = computed(() => browse.fmTracks[currentIndex.value] ?? null);
const currentIsPlaying = computed(() => current.value?.id === player.currentTrack?.id);
const liked = computed(() => Boolean(current.value && detail.favorite[detailContentKey('song', current.value.id)]));
const upcoming = computed(() => browse.fmTracks.slice(currentIndex.value + 1, currentIndex.value + 4));
const listened = computed(() => browse.fmTracks.slice(0, currentIndex.value).slice(-3).reverse());

async function selectTrack(track: TvTrack): Promise<void> {
  if (busy.value) return;
  const ids = browse.fmTracks.map(item => item.id);
  const index = ids.indexOf(track.id);
  if (index < 0) return;
  busy.value = true;
  try { await player.playQueue(ids, index); }
  catch { if (pageActive) show('播放未成功，请重试'); }
  finally { busy.value = false; }
}

function playCurrent(): void {
  if (!current.value || busy.value) return;
  if (currentIsPlaying.value) void player.toggle().catch(() => show('操作未成功，请重试'));
  else void selectTrack(current.value);
}

async function skipCurrent(): Promise<void> {
  const track = current.value;
  if (!track || busy.value) return;
  busy.value = true;
  try {
    const saved = await browse.discardFm(track.id);
    if (!pageActive) return;
    if (!saved) { show('偏好未保存，请重试'); return; }
    let next: TvTrack | undefined = upcoming.value[0];
    if (!next) {
      await browse.loadFm();
      if (!pageActive) return;
      next = browse.fmTracks.find(item => item.id !== track.id);
    }
    if (!next) { show('暂时没有新的推荐，请稍后重试'); return; }
    const ids = browse.fmTracks.filter(item => item.id !== track.id).map(item => item.id);
    await player.playQueue(ids, ids.indexOf(next.id));
    browse.fmTracks = browse.fmTracks.filter(item => item.id !== track.id);
  } catch { if (pageActive) show('切换未成功，请重试'); }
  finally { busy.value = false; }
}

async function likeCurrent(): Promise<void> {
  const track = current.value;
  if (!track || busy.value || liked.value) return;
  busy.value = true;
  const ok = await detail.toggleFavorite('song', track.id, true);
  if (pageActive) show(ok ? '已喜欢，继续聆听' : '收藏未成功，请重试');
  busy.value = false;
}

onMounted(async () => {
  if (browse.fmStatus !== 'ready' || !browse.fmTracks.length) await browse.loadFm();
  void nextTick(() => {
    if (!pageActive || !mayAutoRestorePageFocus('nav-fm')) return;
    tvFocusRegistry.restore(recallFocus(PAGE_KEY) ?? 'fm-current');
  });
});

onBeforeUnmount(() => { pageActive = false; rememberFocus(PAGE_KEY); });
</script>

<template>
  <section class="fm-page" data-page="fm">
    <header class="page-header">
      <span class="eyebrow">PRIVATE FM</span>
      <h1>私人 FM</h1>
      <p>像电台一样流淌，不喜欢就换下一首。</p>
    </header>

    <div v-if="browse.fmStatus === 'ready' && current" class="fm-stage">
      <div class="now-card">
        <span class="now-art" aria-hidden="true"><img v-if="current.artwork" :src="current.artwork" alt="" /><TvIcon v-else name="radio" :size="60" /></span>
        <div class="now-copy">
          <small>{{ currentIsPlaying ? player.isPlaying ? '正在播放' : '已暂停' : '为你推荐' }}</small>
          <h2>{{ current.title }}</h2>
          <p>{{ current.artist }}<template v-if="current.album"> · {{ current.album }}</template></p>
        </div>
        <div class="now-actions">
          <button v-tv-focus type="button" data-focus-id="fm-current" @click="playCurrent"><TvIcon :name="currentIsPlaying && player.isPlaying ? 'pause' : 'play'" /> {{ currentIsPlaying && player.isPlaying ? '暂停' : '播放' }}</button>
          <button v-tv-focus type="button" data-focus-id="fm-like" @click="likeCurrent" :aria-pressed="liked">{{ liked ? '已喜欢' : '喜欢' }}</button>
          <button v-tv-focus type="button" data-focus-id="fm-dislike" @click="skipCurrent">不喜欢</button>
        </div>
      </div>

      <section class="upcoming" aria-label="即将播放">
        <h3>即将播放</h3>
        <button
          v-for="track in upcoming"
          :key="track.id"
          v-tv-focus
          type="button"
          class="upcoming-row"
          :data-focus-id="`fm-upcoming-${track.id}`"
          @click="selectTrack(track)"
        >
          <strong>{{ track.title }}</strong><small>{{ track.artist }}</small>
        </button>
        <p v-if="!upcoming.length" class="empty">本批推荐已到末尾，可返回发现更多音乐。</p>
      </section>

      <section class="listened" aria-label="已听过">
        <h3>刚刚听过</h3>
        <p v-for="track in listened" :key="track.id" class="listened-row"><strong>{{ track.title }}</strong><small>{{ track.artist }}</small></p>
        <p v-if="!listened.length" class="empty">还没有播放记录</p>
      </section>
    </div>

    <button
      v-else-if="browse.fmStatus === 'error'"
      v-tv-focus
      type="button"
      data-focus-id="fm-retry"
      @click="browse.loadFm()"
    >FM 加载失败，按确认重试</button>
    <p v-else class="empty">正在准备你的私人电台…</p>

    <p v-if="notice || busy" class="fm-notice" role="status">{{ busy ? '正在处理…' : notice }}</p>
  </section>
</template>

<style scoped>
.fm-page { padding: 4px 14px 150px; min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size: 44px; letter-spacing: -1.6px; }
.page-header p { margin: 10px 0 0; color: var(--tv-text-secondary); font-size: 17px; }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.fm-stage { margin-top: 24px; }
.now-card { display: flex; align-items: center; gap: 28px; padding: 26px 32px; border-radius: var(--tv-radius-panel); background: linear-gradient(105deg, #112d58 0%, #123b75 42%, #18152d 100%); box-shadow: 0 24px 70px rgba(0, 0, 0, 0.3); }
.now-art { flex: none; width: 130px; height: 130px; display: grid; place-items: center; border-radius: 22px; font-size: 44px; background: linear-gradient(145deg, #2499ff, #7845c0 58%, #ff607e); box-shadow: 0 26px 60px rgba(0, 0, 0, 0.38); }
.now-copy { min-width: 0; flex: 1; }
.now-copy small { color: #72b6ff; font-size: 13px; letter-spacing: 1.6px; font-weight: 700; }
.now-copy h2 { margin: 8px 0 4px; font-size: 34px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.now-copy p { margin: 0; color: #d8e6f7; font-size: 16px; }
.now-actions { display: flex; gap: 10px; }
.now-actions button { min-width: 104px; height: 52px; border: 0; border-radius: 26px; font-size: 15px; font-weight: 700; color: white; background: rgba(255, 255, 255, 0.12); }
.now-actions button:first-child { color: #07111d; background: white; }
.upcoming, .listened { margin-top: 26px; }
.upcoming h3, .listened h3 { margin: 0 0 12px; font-size: 22px; }
.upcoming-row { display: flex; align-items: baseline; gap: 14px; width: 100%; min-height: 60px; margin-top: 8px; padding: 0 18px; border: 1px solid transparent; border-radius: var(--tv-radius-card); color: var(--tv-text); text-align: left; background: rgba(255, 255, 255, 0.03); }
.upcoming-row strong { font-size: 16px; }
.upcoming-row small { color: var(--tv-text-secondary); font-size: 14px; }
.listened-row { display: flex; align-items: baseline; gap: 14px; padding: 4px 2px; color: var(--tv-text-secondary); }
.listened-row strong { font-size: 15px; color: var(--tv-text); }
.listened-row small { font-size: 13px; }
.empty { padding: 14px 2px; color: var(--tv-text-secondary); font-size: 15px; }
.fm-page > button { min-width: 260px; height: 52px; margin-top: 20px; border: 1px solid rgba(124, 188, 255, 0.35); border-radius: 26px; color: #dceeff; background: rgba(36, 102, 164, 0.3); }
.now-card { background:linear-gradient(120deg,#322731,#242227); border:1px solid var(--tv-border); }
.now-art { width:176px; height:176px; overflow:hidden; background:#3c303c; }.now-art img { width:100%; height:100%; object-fit:cover; }
.now-copy small { color:var(--tv-accent); font-size:19px; }.now-copy p { font-size:22px; }
.now-actions button { min-height:66px; font-size:23px; }.now-actions button[aria-pressed=true] { color:var(--tv-accent); }
.upcoming-row { min-height:80px; }.upcoming-row strong { font-size:25px; }.upcoming-row small { font-size:21px; }
.listened-row strong { font-size:22px; }.listened-row small,.empty { font-size:20px; }
.fm-notice { color:var(--tv-accent); font-size:22px; }
</style>
