<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import TvIcon from '../components/TvIcon.vue';
import ContentSkeleton from '../content/ContentSkeleton.vue';
import RankingArtwork from '../content/RankingArtwork.vue';
import PaginationStatus from '../content/PaginationStatus.vue';
import SongActions from '../content/SongActions.vue';
import SongList from '../content/SongList.vue';
import { recallFocus, rememberFocus, sourceFocusFromEvent } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import { useBackLayerRegistry } from '../input/backCoordinator';
import type { TvRankingSummary } from '../native/contentTypes';
import type { TvTrack } from '../stores/app';
import { useTvBrowseStore } from '../stores/browse';
import { useTvPlayerStore } from '../stores/player';

const route = useRoute();
const router = useRouter();
const browse = useTvBrowseStore();
const player = useTvPlayerStore();
const backLayers = useBackLayerRegistry();
const rankingId = computed(() => String(route.params.id ?? ''));
const isDetail = computed(() => Boolean(rankingId.value));
const rankingShelf = computed(() => browse.shelves.find(shelf => shelf.id === 'rankings'));
const rankings = computed(() => rankingShelf.value?.rankings ?? []);
const currentRanking = computed(() => rankings.value.find(item => item.id === rankingId.value));
const rankingTitle = computed(() => currentRanking.value?.title ?? '排行榜');
const menuTrack = ref<TvTrack | null>(null);
const menuSourceFocusId = ref<string | undefined>(undefined);
let releaseBackLayer: (() => void) | undefined;
let alive = true;
let routeGeneration = 0;
const trackIds = computed(() => browse.rankingTracks.map(track => track.id));
const firstTrack = computed(() => browse.rankingTracks.length ? `ranking-${browse.rankingTracks[0].id}`
  : browse.rankingStatus === 'error' ? 'ranking-retry' : 'ranking-back');
const memoryKey = computed(() => isDetail.value ? `ranking-detail-${rankingId.value}` : 'ranking');
const defaultFocus = computed(() => {
  const remembered = recallFocus(memoryKey.value);
  const validIds = isDetail.value
    ? trackIds.value.map(id => `ranking-${id}`).concat(trackIds.value.length ? ['ranking-play-all', 'ranking-back'] : ['ranking-retry', 'ranking-back'])
    : rankings.value.map((_, index) => `ranking-list-${index}`);
  return remembered && validIds.includes(remembered) ? remembered
    : isDetail.value ? trackIds.value.length ? 'ranking-play-all' : 'ranking-retry' : 'ranking-list-0';
});

function activeId(): string | undefined { return (document.activeElement as HTMLElement | null)?.dataset.focusId; }
function goBack(): void {
  const fromExplore = String(route.query.sourceFocusId ?? '').startsWith('explore-');
  void router.push(fromExplore ? { name: 'explore', query: { category: route.query.sourceCategory ?? 'all' } } : { name: 'ranking' });
}
function openRanking(ranking: TvRankingSummary, event: Event): void {
  rememberFocus('ranking');
  void router.push({ name: 'ranking-detail', params: { id: ranking.id }, query: { sourceFocusId: sourceFocusFromEvent(event) } });
}
function playTrack(_track: TvTrack, index = 0): void {
  if (trackIds.value.length) void player.playQueue(trackIds.value, index).catch(() => undefined);
}

async function loadRoute(): Promise<void> {
  const generation = ++routeGeneration;
  const id = rankingId.value;
  releaseBackLayer?.();
  // Back must work even while the provider is still loading.
  releaseBackLayer = id ? backLayers.register({ kind: 'page', close: goBack }) : undefined;
  if (id) {
    // Preserve loaded pages when returning from playback.
    if (browse.rankingId !== id || browse.rankingStatus !== 'ready') await browse.loadRanking(id);
    if (!browse.loaded) void browse.loadExplore();
  } else if (!browse.loaded) await browse.loadExplore();
  await nextTick();
  if (!alive || generation !== routeGeneration || (activeId() && activeId() !== 'nav-ranking')) return;
  const fallback = id ? browse.rankingTracks.length ? 'ranking-play-all' : firstTrack.value : 'ranking-list-0';
  const remembered = recallFocus(memoryKey.value);
  const exists = remembered && Array.from(document.querySelectorAll<HTMLElement>('[data-focus-id]')).some(el => el.dataset.focusId === remembered);
  tvFocusRegistry.restore(exists ? remembered : fallback);
}

async function retryDetail(): Promise<void> {
  const id = rankingId.value;
  if (browse.rankingStatus === 'loading') return;
  const origin = document.activeElement;
  await browse.loadRanking(id);
  await nextTick();
  if (alive && rankingId.value === id && (!activeId() || document.activeElement === origin)) tvFocusRegistry.restore(firstTrack.value);
}

watch(rankingId, () => { void loadRoute(); }, { immediate: true });
onBeforeUnmount(() => {
  alive = false;
  routeGeneration++;
  releaseBackLayer?.();
});
</script>

<template>
  <section class="ranking-page core-browse-page" data-page="ranking" :data-default-focus-id="defaultFocus" @focusin="rememberFocus(memoryKey)">
    <template v-if="!isDetail">
      <header class="page-header"><span class="eyebrow">CHARTS</span><h1>排行榜</h1><p>此刻，大家都在听</p></header>
      <ContentSkeleton v-if="!browse.loaded || rankingShelf?.status === 'loading'" :count="8" label="正在加载排行榜" />
      <div v-else-if="rankings.length" class="ranking-grid">
        <button v-for="(ranking, index) in rankings" :key="ranking.id" v-tv-focus type="button" class="ranking-card tv-card"
          :data-focus-id="`ranking-list-${index}`" data-focus-group="ranking-list"
          :data-focus-left="index % 4 === 0 ? 'nav-ranking' : `ranking-list-${index - 1}`"
          :data-focus-right="`ranking-list-${index % 4 === 3 ? index : Math.min(index + 1, rankings.length - 1)}`"
          :data-focus-up="index < 4 ? `ranking-list-${index}` : `ranking-list-${index - 4}`"
          :data-focus-down="index + 4 < rankings.length ? `ranking-list-${index + 4}` : `ranking-list-${index}`"
          @click="openRanking(ranking, $event)">
          <RankingArtwork :artwork="ranking.artwork" :index="index" />
          <strong>{{ ranking.title }}</strong><small>查看榜单 <TvIcon name="chevronRight" :size="18" /></small>
        </button>
      </div>
      <div v-else class="empty-state"><TvIcon name="chart" :size="48" /><h2>{{ rankingShelf?.status === 'error' ? '排行榜加载失败' : '暂时没有可用榜单' }}</h2>
        <button v-tv-focus type="button" data-focus-id="ranking-list-0" data-focus-left="nav-ranking" @click="browse.loadExplore()">重新加载</button>
      </div>
    </template>
    <template v-else>
      <header class="ranking-hero">
        <RankingArtwork :artwork="currentRanking?.artwork ?? ''" />
        <div class="hero-copy"><span class="eyebrow">CHARTS</span><h1>{{ rankingTitle }}</h1>
          <p>{{ browse.rankingStatus === 'loading' && !trackIds.length ? '正在加载榜单…' : trackIds.length ? `已加载 ${trackIds.length} 首${browse.rankingTotal !== null ? ' · 共 ' + browse.rankingTotal + ' 首' : ''}` : '排行榜音乐' }}</p>
          <div class="toolbar">
            <button v-tv-focus type="button" class="primary" :data-focus-id="trackIds.length ? 'ranking-play-all' : 'ranking-retry'" data-focus-left="nav-ranking" data-focus-right="ranking-back" :data-focus-down="firstTrack" :aria-busy="browse.rankingStatus === 'loading'" @click="trackIds.length ? playTrack(browse.rankingTracks[0]) : retryDetail()"><TvIcon :name="trackIds.length ? 'play' : 'refresh'" :size="24" />{{ trackIds.length ? (browse.rankingNextCursor || (browse.rankingTotal ?? 0) > trackIds.length ? `播放已加载的 ${trackIds.length} 首` : '播放全部') : browse.rankingStatus === 'loading' ? '正在加载…' : '重新加载' }}</button>
            <button v-tv-focus type="button" data-focus-id="ranking-back" :data-focus-left="trackIds.length ? 'ranking-play-all' : 'ranking-retry'" data-focus-right="ranking-back" :data-focus-down="firstTrack" @click="goBack"><TvIcon name="back" :size="24" />返回榜单</button>
          </div>
        </div>
      </header>
      <ContentSkeleton v-if="browse.rankingStatus === 'loading' && !trackIds.length" variant="songs" :count="4" label="正在加载榜单歌曲" />
      <SongList v-else-if="trackIds.length" :tracks="browse.rankingTracks" group="ranking" up-focus-id="ranking-play-all" :down-focus-id="browse.rankingStatus === 'error' ? 'pagination-retry' : browse.rankingNextCursor ? 'pagination-load-more' : undefined" left-focus-id="nav-ranking"
        @play="playTrack" @menu="(track) => { menuTrack = track; menuSourceFocusId = `ranking-${track.id}`; }" />
      <div v-else class="empty-state"><h2>{{ browse.rankingStatus === 'error' ? '榜单歌曲加载失败' : '这个榜单暂时没有歌曲' }}</h2><p>可以返回选择其他榜单，或重新加载。</p>
      </div>
      <PaginationStatus v-if="trackIds.length" :loading="browse.rankingStatus === 'loading'" :has-more="Boolean(browse.rankingNextCursor)" :error="browse.rankingStatus === 'error'"
        :focus-context="rankingId" :item-focus-ids="trackIds.map(id => `ranking-${id}`)"
        left-focus-id="nav-ranking" :up-focus-id="`ranking-${trackIds[trackIds.length - 1]}`" @load-more="browse.loadRanking(rankingId, browse.rankingNextCursor ?? undefined)" @retry="browse.loadRanking(rankingId, browse.rankingNextCursor ?? undefined)" />
    </template>
    <SongActions :track="menuTrack" :source-focus-id="menuSourceFocusId" @close="menuTrack = null" />
  </section>
</template>

<style scoped>
.ranking-page { padding:12px 22px var(--tv-content-bottom-safe); min-width:0; }
.page-header { padding:10px 4px 26px; }h1 { margin:8px 0; font-size:54px; line-height:1.15; letter-spacing:-1.5px; }
.eyebrow { color:var(--tv-accent); font-size:16px; letter-spacing:3px; font-weight:650; }
.page-header p,.hero-copy p { margin:12px 0 0; color:var(--tv-text-secondary); font-size:22px; }
.ranking-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:30px 24px; padding:4px; }
.ranking-card { min-width:0; padding:8px; border:0; border-radius:22px; color:var(--tv-text); text-align:left; background:transparent; }.ranking-card:focus { background:var(--tv-card-focus); }
.ranking-card strong,.ranking-card small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.ranking-card strong { margin:16px 2px 6px; font-size:24px; }.ranking-card small { margin:0 2px 6px; font-size:20px; color:var(--tv-text-secondary); }
.ranking-hero { display:flex; gap:36px; align-items:center; padding:12px 12px 32px; }.ranking-hero > .ranking-artwork { width:230px; flex:none; box-shadow:0 20px 50px #0005; }
.hero-copy { min-width:0; }.hero-copy h1 { font-size:46px; }.toolbar { display:flex; gap:18px; margin-top:26px; }
.toolbar button,.empty-state button { display:inline-flex; align-items:center; justify-content:center; gap:12px; min-height:60px; padding:0 28px; border:1px solid var(--tv-border); border-radius:16px; color:var(--tv-text); background:var(--tv-glass); font-size:23px; }
.toolbar .primary { color:#201d25; background:#f6f3f8; }
.empty-state { padding:32px 26px; border-radius:24px; background:#ffffff05; color:var(--tv-text-secondary); }.empty-state h2 { color:var(--tv-text); font-size:29px; }.empty-state p { font-size:22px; }
</style>
