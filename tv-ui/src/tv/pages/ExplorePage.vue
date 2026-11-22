<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import ContentSkeleton from '../content/ContentSkeleton.vue';
import RankingArtwork from '../content/RankingArtwork.vue';
import { computed, nextTick, onBeforeUnmount, onMounted, reactive } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ContentGrid from '../content/ContentGrid.vue';
import MediaShelf from '../content/MediaShelf.vue';
import { recallFocus, rememberFocus, sourceFocusFromEvent, mayAutoRestorePageFocus } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import type { TvContentCard, TvRankingSummary } from '../native/contentTypes';
import { useTvBrowseStore, type ExploreShelf } from '../stores/browse';

const PAGE_KEY = 'explore';
const browse = useTvBrowseStore();
const router = useRouter();
const route = useRoute();
const categories = [
  { id: 'all', label: '精选' }, { id: 'newPlaylists', label: '歌单' },
  { id: 'newSongs', label: '新歌' }, { id: 'newAlbums', label: '专辑' },
  { id: 'artists', label: '歌手' }, { id: 'rankings', label: '排行榜' },
];
const category = computed(() => categories.some(tab => tab.id === route.query.category) ? String(route.query.category) : 'all');
const tabFocus = computed(() => `explore-tab-${category.value}`);
const retrying = reactive(new Set<ExploreShelf['id']>());
const visibleCounts = reactive<Record<string, number>>({});
function shelfCards(shelf: ExploreShelf): TvContentCard[] {
  return shelf.cards.slice(0, category.value === 'all' ? 12 : visibleCounts[shelf.id] ?? 60);
}
function shelfRankings(shelf: ExploreShelf): TvRankingSummary[] {
  return category.value === 'all' ? shelf.rankings.slice(0, 8) : shelf.rankings;
}
let pageActive = true;
const visibleShelves = computed(() => browse.shelves.filter((shelf) => {
  if (category.value !== 'all') return shelf.id === category.value;
  if (retrying.has(shelf.id)) return true;
  if (shelf.status !== 'ready') return true;
  if (shelf.id === 'rankings') return shelf.rankings.length > 0;
  return shelf.cards.length > 0 || shelf.tracks.length > 0;
}));

function shelfTarget(row: number, index: number): string {
  const shelf = visibleShelves.value[row];
  if (!shelf) return row < 0 ? tabFocus.value : 'mini-open';
  if (shelf.status === 'error') return `explore-${shelf.id}-retry`;
  if (shelf.id === 'rankings') return shelf.rankings.length ? `explore-rankings-${shelfRankings(shelf)[Math.min(index, shelfRankings(shelf).length - 1)].id}` : tabFocus.value;
  return shelf.cards.length ? `explore-${shelf.id}-${Math.min(index, shelfCards(shelf).length - 1)}` : tabFocus.value;
}
async function selectCategory(id: string, enterContent = false): Promise<void> {
  // The heading action disappears after filtering. Transfer to a persistent
  // category first so the shell cannot repair its focus to an unrelated item.
  if (enterContent) tvFocusRegistry.restore(`explore-tab-${id}`);
  await router.replace({ name: 'explore', query: { category: id } });
  await nextTick();
  if (enterContent && pageActive && category.value === id && (document.activeElement as HTMLElement)?.dataset.focusId === `explore-tab-${id}`) {
    tvFocusRegistry.restore(shelfTarget(0, 0));
  }
}

const detailRoutes: Record<string, string> = {
  playlist: 'playlist-detail',
  album: 'album-detail',
  artist: 'artist-detail',
  song: 'song-detail',
  mv: 'mv-detail',
};

function openCard(card: TvContentCard, event?: Event): void {
  const name = detailRoutes[card.kind];
  if (!name) return;
  const focusId = event ? sourceFocusFromEvent(event) : undefined;
  void router.push({
    name,
    params: { id: card.id },
    ...(focusId ? { query: { sourceFocusId: focusId } } : {}),
  });
}

function openRanking(ranking: TvRankingSummary, event?: Event): void {
  const focusId = event ? sourceFocusFromEvent(event) : undefined;
  void router.push({
    name: 'ranking-detail',
    params: { id: ranking.id },
    query: { sourceFocusId: focusId, sourceCategory: category.value },
  });
}

function gridGroup(shelfId: string): string {
  return `explore-${shelfId}`;
}

function focusTargetExists(id: string | undefined): id is string {
  if (!id) return false;
  return Array.from(document.querySelectorAll<HTMLElement>('[data-focus-id]'))
    .some((element) => element.dataset.focusId === id);
}

function defaultFocusId(): string {
  for (const shelf of visibleShelves.value) {
    if (shelf.status === 'error') return `explore-${shelf.id}-retry`;
    if (shelf.id === 'rankings') {
      const first = shelf.rankings[0];
      if (first) return `explore-${shelf.id}-${first.id}`;
      continue;
    }
    if (shelf.status === 'ready' && (shelf.cards.length || shelf.tracks.length)) {
      return `${gridGroup(shelf.id)}-0`;
    }
  }
  return 'nav-explore';
}

async function retryShelf(id: ExploreShelf['id'], event: Event): Promise<void> {
  if (retrying.has(id)) return;
  const source = event.currentTarget as HTMLElement;
  retrying.add(id);
  try {
    await browse.retryShelf(id);
  } finally {
    // Keep the source mounted through the store update, then transfer only
    // focus still owned by this action. Navigating away must remain possible.
    const restoreOwnedFocus = pageActive && document.activeElement === source;
    retrying.delete(id);
    await nextTick();
    if (restoreOwnedFocus && pageActive) {
      const shelf = browse.shelves.find(item => item.id === id);
      const target = shelf?.status === 'error' ? `explore-${id}-retry`
        : id === 'rankings' && shelf?.rankings.length ? `explore-rankings-${shelf.rankings[0].id}`
        : shelf && (shelf.cards.length || shelf.tracks.length) ? `${gridGroup(id)}-0`
        : focusTargetExists('explore-first') ? 'explore-first' : defaultFocusId();
      tvFocusRegistry.restore(target);
    }
  }
}

onMounted(async () => {
  if (!browse.loaded) await browse.loadExplore();
  void nextTick(() => {
    if (!pageActive || !mayAutoRestorePageFocus('nav-explore')) return;
    const remembered = recallFocus(PAGE_KEY);
    tvFocusRegistry.restore(focusTargetExists(remembered) ? remembered : defaultFocusId());
  });
});

onBeforeUnmount(() => { pageActive = false; rememberFocus(PAGE_KEY); });
</script>

<template>
  <section class="explore-page core-browse-page" data-page="explore">
    <header class="page-header">
      <span class="eyebrow">DISCOVER</span>
      <h1>发现</h1>
      <p>发现下一首喜欢的歌</p>
    </header>

    <nav class="category-tabs" aria-label="发现分类">
      <button v-for="(tab, index) in categories" :key="tab.id" v-tv-focus type="button"
        :data-focus-id="`explore-tab-${tab.id}`" :aria-pressed="category === tab.id"
        :data-focus-left="index ? `explore-tab-${categories[index - 1].id}` : 'nav-explore'"
        :data-focus-right="`explore-tab-${categories[Math.min(index + 1, categories.length - 1)].id}`"
        :data-focus-down="visibleShelves.length ? shelfTarget(0, 0) : 'explore-first'"
        @click="selectCategory(tab.id)">{{ tab.label }}</button>
    </nav>

    <template v-if="browse.loaded && visibleShelves.length">
      <section v-for="(shelf, shelfIndex) in visibleShelves" :key="shelf.id" class="shelf" :aria-labelledby="`${shelf.id}-title`">
        <div class="shelf-heading"><h2 :id="`${shelf.id}-title`">{{ shelf.title }}</h2><button v-if="category === 'all' && shelf.status === 'ready'" v-tv-focus class="see-all" type="button" :data-focus-id="`explore-all-${shelf.id}`" :data-focus-down="shelfTarget(shelfIndex, 0)" data-focus-left="nav-explore" @click="selectCategory(shelf.id, true)">查看全部 <TvIcon name="chevronRight" :size="20" /></button></div>
        <ContentSkeleton v-if="shelf.status === 'loading' && !retrying.has(shelf.id)" :variant="category === 'all' ? 'shelf' : 'cards'" :round="shelf.id === 'artists'" :label="`正在加载${shelf.title}`" />
        <button v-else-if="shelf.status === 'error' || retrying.has(shelf.id)" v-tv-focus type="button" class="shelf-error" :data-focus-id="`explore-${shelf.id}-retry`" data-focus-left="nav-explore" :aria-busy="retrying.has(shelf.id)" @click="retryShelf(shelf.id, $event)">{{ retrying.has(shelf.id) ? `${shelf.title}加载中…` : `${shelf.title}加载失败，按确认重试` }}</button>
        <template v-else-if="shelf.id !== 'rankings'">
          <MediaShelf v-if="category === 'all' && shelf.status === 'ready' && shelf.cards.length" :cards="shelfCards(shelf)" :group="gridGroup(shelf.id)" left-focus-id="nav-explore" :up-focus-id="(index) => shelfTarget(shelfIndex - 1, index)" :down-focus-id="(index) => shelfTarget(shelfIndex + 1, index)" @select="openCard" />
          <ContentGrid
            v-else-if="shelf.status === 'ready' && (shelf.cards.length || shelf.tracks.length)"
            :cards="shelf.cards.length ? shelfCards(shelf) : shelf.tracks.map((track) => ({ id: track.id, title: track.title, kind: 'song', subtitle: track.artist, artwork: track.artwork }))"
            :group="gridGroup(shelf.id)"
            left-focus-id="nav-explore"
            :columns="4"
            :up-focus-id="tabFocus"
            @select="(card, event) => openCard(card, event)"
          />
          <p v-else-if="shelf.status === 'ready'" class="empty">{{ shelf.id === 'newSongs' ? '暂无新歌' : '暂无内容' }}</p>
          <button v-if="category !== 'all' && shelf.cards.length > shelfCards(shelf).length" v-tv-focus type="button" class="see-all" :data-focus-id="`explore-more-${shelf.id}`" data-focus-left="nav-explore" @click="visibleCounts[shelf.id] = (visibleCounts[shelf.id] ?? 60) + 60">显示更多 · 已显示 {{ shelfCards(shelf).length }} / {{ shelf.cards.length }}</button>
        </template>
        <div v-else class="ranking-grid">
          <button
            v-for="(ranking, index) in shelfRankings(shelf)"
            :key="ranking.id"
            v-tv-focus
            type="button"
            class="ranking-card tv-card"
            :data-focus-id="`explore-${shelf.id}-${ranking.id}`"
            :data-focus-left="index % 4 === 0 ? 'nav-explore' : undefined"
            :data-focus-up="index < 4 ? category === 'all' ? shelfTarget(shelfIndex - 1, index) : tabFocus : `explore-rankings-${shelf.rankings[index - 4].id}`"
            :data-focus-down="index + 4 < shelfRankings(shelf).length ? `explore-rankings-${shelfRankings(shelf)[index + 4].id}` : category === 'all' && shelf.rankings.length > 8 ? 'explore-rankings-more' : `explore-rankings-${ranking.id}`"
            @click="openRanking(ranking, $event)"
          >
            <RankingArtwork :artwork="ranking.artwork" :index="index" />
            <strong>{{ ranking.title }}</strong>
          </button>
          <p v-if="shelf.status === 'ready' && !shelf.rankings.length" class="empty">暂无榜单</p>
          <button v-if="category === 'all' && shelf.rankings.length > 8" v-tv-focus type="button" class="see-all rankings-more" data-focus-id="explore-rankings-more" :data-focus-up="`explore-rankings-${shelfRankings(shelf)[shelfRankings(shelf).length - 1].id}`" data-focus-down="explore-rankings-more" @click="selectCategory(shelf.id, true)">查看全部 {{ shelf.rankings.length }} 个榜单 <TvIcon name="chevronRight" :size="22" /></button>
        </div>
      </section>
    </template>

    <div v-else-if="browse.loaded" class="skeleton">
      <p>内容暂时不可用</p>
      <button v-tv-focus type="button" data-focus-id="explore-first" data-focus-left="nav-explore" @click="browse.loadExplore()">重新加载</button>
    </div>
    <div v-else class="skeleton" aria-label="加载中">
      <ContentSkeleton v-for="index in 3" :key="index" :variant="category === 'all' ? 'shelf' : 'cards'" :round="category === 'artists'" heading :label="index === 1 ? '正在加载发现内容' : '正在加载更多内容'" />
    </div>
  </section>
</template>

<style scoped>
.explore-page { padding:12px 22px var(--tv-content-bottom-safe); min-width:0; }
.page-header { padding:10px 4px 26px; }.page-header h1 { margin:8px 0; font-size:54px; line-height:1.1; letter-spacing:-1.5px; }
.page-header p { margin:12px 0 0; color:var(--tv-text-secondary); font-size:22px; }.eyebrow { color:var(--tv-accent); font-size:16px; letter-spacing:3px; font-weight:650; }
.category-tabs { display:flex; gap:16px; padding:10px 6px 24px; }.category-tabs button { min-height:60px; padding:0 26px; border:0; border-radius:16px; font-size:25px; font-weight:600; background:transparent; color:var(--tv-text-secondary); }
.category-tabs button[aria-pressed="true"] { background:#ffffff1f; color:var(--tv-text); }
.shelf { margin-top:20px; }.shelf-heading { display:flex; align-items:center; justify-content:space-between; margin:0 6px 6px; }.shelf-heading h2 { margin:0; font-size:29px; }
.see-all,.shelf-error,.skeleton button { min-height:56px; padding:0 24px; border:1px solid var(--tv-border); border-radius:16px; font-size:21px; background:var(--tv-glass); color:var(--tv-text); }
.shelf-error { margin:20px 8px; }.empty,.skeleton p { padding:20px 8px; color:var(--tv-text-secondary); font-size:23px; }
.ranking-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:30px 24px; padding:4px; }
.ranking-card { min-width:0; padding:8px; border:0; border-radius:22px; color:var(--tv-text); text-align:left; background:transparent; }.ranking-card:focus { background:var(--tv-card-focus); }
.ranking-art { aspect-ratio:1; display:grid; place-items:center; border-radius:16px; overflow:hidden; font-size:90px; font-weight:750; background:linear-gradient(140deg,#69424e,#26232e); }.ranking-art img { width:100%; height:100%; object-fit:cover; }
.ranking-card strong { display:block; margin:16px 2px 8px; font-size:24px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.rankings-more { grid-column:1/-1; justify-self:center; min-height:64px; display:flex; align-items:center; gap:10px; }
:deep(.content-grid .card-art) { aspect-ratio:1; }:deep(.content-grid .grid) { gap:30px 24px; }:deep(.content-card) { padding:8px; border:0; border-radius:22px; background:transparent; }:deep(.content-card:focus) { background:var(--tv-card-focus); }
:deep(.content-card strong) { margin-top:16px; }

</style>
