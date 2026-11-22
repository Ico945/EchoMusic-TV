<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import ContentGrid from '../content/ContentGrid.vue';
import DetailHero from '../content/DetailHero.vue';
import SongActions from '../content/SongActions.vue';
import SongList from '../content/SongList.vue';
import { useTransientNotice } from '../content/notice';
import { usePaginationFocus } from '../content/paginationFocus';
import { recallFocus, rememberFocus } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import { useBackLayerRegistry } from '../input/backCoordinator';
import type { TvContentCard } from '../native/contentTypes';
import type { TvTrack } from '../stores/app';
import { detailChildKey, detailContentKey, useTvDetailStore } from '../stores/detail';
import { useTvPlayerStore } from '../stores/player';

const route = useRoute();
const router = useRouter();
const detailStore = useTvDetailStore();
const player = useTvPlayerStore();
const backLayers = useBackLayerRegistry();
const { notice, show } = useTransientNotice();

const kind = 'artist';
const id = computed(() => String(route.params.id ?? ''));
const tabs = ['songs', 'albums', 'mv'] as const;
const activeTab = ref<(typeof tabs)[number]>('songs');
const menuTrack = ref<TvTrack | null>(null);
const menuSourceFocusId = ref<string | undefined>(undefined);
let releaseBackLayer: (() => void) | undefined;
let pageActive = false;
let loadGeneration = 0;
let pendingContentFocus: string | undefined;
const loading = ref(false);
const pageElement = ref<HTMLElement | null>(null);

const detail = computed(() => {
  const value = detailStore.detail;
  return !loading.value && value?.kind === kind && detailContentKey(kind, value.id) === detailContentKey(kind, id.value) ? value : null;
});
const emptyPage = { items: [], tracks: [], nextCursor: null, total: null };
const songsPage = computed(() => detailStore.children[detailChildKey(kind, id.value, 'songs')] ?? emptyPage);
const albumsPage = computed(() => detailStore.children[detailChildKey(kind, id.value, 'albums')] ?? emptyPage);
const mvPage = computed(() => detailStore.children[detailChildKey(kind, id.value, 'mv')] ?? emptyPage);
const childStatus = computed(() => detailStore.childrenStatus[detailChildKey(kind, id.value, activeTab.value)]);
const isFavorite = computed(() => detailStore.favoriteState(kind, id.value) ?? false);
const firstContentFocusId = computed(() => {
  if (activeTab.value === 'songs' && songsPage.value.tracks[0]) return `artist-${songsPage.value.tracks[0].id}`;
  if (currentPage().items.length) return `artist-${activeTab.value}-0`;
  return childStatus.value === 'loading' || childStatus.value === 'error' ? 'artist-page-action' : undefined;
});
const contentFocusIds = computed(() => activeTab.value === 'songs'
  ? songsPage.value.tracks.map(track => `artist-${track.id}`)
  : currentPage().items.map((_, index) => `artist-${activeTab.value}-${index}`));
const lastContentFocusId = computed(() => contentFocusIds.value[contentFocusIds.value.length - 1] ?? `artist-tab-${activeTab.value}`);
const { retainAction, begin: beginPaginationFocus } = usePaginationFocus({
  scope: () => pageElement.value,
  itemFocusIds: () => contentFocusIds.value,
  loading: () => childStatus.value === 'loading',
  error: () => childStatus.value === 'error',
  hasMore: () => Boolean(currentPage().nextCursor),
  context: () => `${id.value}:${activeTab.value}:${loadGeneration}`,
  fallbackFocusId: () => `artist-tab-${activeTab.value}`,
});

// The shell's detached-focus repair must stay on this page while a loading
// control is replaced, before the async page continuation resumes.
const defaultFocusId = computed(() => loading.value || !detail.value ? 'artist-retry'
  : activeTab.value === 'songs' && songsPage.value.tracks.length ? 'detail-primary' : `artist-tab-${activeTab.value}`);

const hotTrackIds = computed(() => songsPage.value.tracks.map((track) => track.id));

function playAll(): void {
  if (!hotTrackIds.value.length) return;
  void player.playQueue(hotTrackIds.value, 0).catch(() => undefined);
}

function playTrack(track: TvTrack): void {
  void player.playQueue(hotTrackIds.value, Math.max(0, songsPage.value.tracks.findIndex((item) => item.id === track.id))).catch(() => undefined);
}

function toggleFavorite(): void {
  if (!detail.value) return;
  const targetId = id.value;
  const next = !isFavorite.value;
  void detailStore.toggleFavorite('artist', targetId, next).then(() => {
    if (!pageActive || targetId !== id.value) return;
    show(isFavorite.value === next ? (next ? '已关注' : '已取消关注') : '操作未成功，请重试');
  });
}

function currentPage() {
  if (activeTab.value === 'songs') return songsPage.value;
  if (activeTab.value === 'albums') return albumsPage.value;
  return mvPage.value;
}

function switchTab(tab: (typeof tabs)[number]): void {
  activeTab.value = tab;
  if (!childStatus.value) void loadChildPage();
}

async function loadChildPage(event?: MouseEvent): Promise<void> {
  if (childStatus.value === 'loading') return;
  if (event) beginPaginationFocus(event.currentTarget as HTMLElement);
  const tab = activeTab.value;
  const generation = loadGeneration;
  const source = document.activeElement as HTMLElement | null;
  await detailStore.loadChildren(kind, id.value, tab, currentPage().nextCursor ?? undefined);
  await nextTick();
  if (!pageActive || generation !== loadGeneration || tab !== activeTab.value) return;
  const pending = pendingContentFocus;
  const ownsFocus = document.activeElement === source || (!source?.isConnected && document.activeElement === document.body);
  if (pending && ownsFocus && (source?.dataset.focusId === 'artist-page-action' || source?.dataset.focusId === `artist-tab-${tab}`)) {
    const target = Array.from(pageElement.value?.querySelectorAll<HTMLElement>('[data-focus-id]') ?? [])
      .find(node => node.dataset.focusId === pending);
    if (target) { pendingContentFocus = undefined; tvFocusRegistry.restore(pending); return; }
  } else if (pending && !ownsFocus) pendingContentFocus = undefined;
}

function openCard(card: TvContentCard): void {
  const routes: Record<string, string> = { album: 'album-detail', playlist: 'playlist-detail', mv: 'mv-detail', artist: 'artist-detail', song: 'song-detail' };
  const name = routes[card.kind];
  if (!name) return;
  const focusId = (document.activeElement as HTMLElement | null)?.dataset.focusId;
  rememberPageFocus(id.value);
  void router.push({
    name, params: { id: card.detailId ?? card.id },
    query: { ...(focusId ? { sourceFocusId: focusId } : {}), ...(card.kind === 'mv' ? { title: card.title, artist: card.subtitle } : {}) },
  });
}

function openMenu(track: TvTrack): void {
  menuTrack.value = track;
  menuSourceFocusId.value = `artist-${track.id}`;
}

function pageKey(artistId: string): string { return `artist-detail:${detailContentKey(kind, artistId)}`; }
function rememberPageFocus(artistId: string): void {
  if (pageElement.value?.contains(document.activeElement)) rememberFocus(pageKey(artistId));
}

async function loadPage(initial = false): Promise<void> {
  if (!initial && loading.value) return;
  const generation = ++loadGeneration;
  pendingContentFocus = undefined;
  loading.value = true;
  const previousFocus = document.activeElement;
  const pending = detailStore.loadDetail(kind, id.value);
  await nextTick();
  if (!pageActive || generation !== loadGeneration) return;
  if (initial && (document.activeElement === previousFocus || (!previousFocus?.isConnected && document.activeElement === document.body))) {
    tvFocusRegistry.restore('artist-retry');
  }
  await pending;
  if (!pageActive || generation !== loadGeneration) return;
  const focused = document.activeElement as HTMLElement | null;
  const ownsRetryFocus = focused?.dataset.focusId === 'artist-retry';
  loading.value = false;
  await nextTick();
  if (!pageActive || generation !== loadGeneration) return;
  if (ownsRetryFocus && (document.activeElement === focused || document.activeElement === document.body ||
    (document.activeElement as HTMLElement | null)?.dataset.focusId === defaultFocusId.value)) {
    const remembered = recallFocus(pageKey(id.value));
    const exists = Array.from(pageElement.value?.querySelectorAll<HTMLElement>('[data-focus-id]') ?? [])
      .some(node => node.dataset.focusId === remembered);
    pendingContentFocus = !exists ? remembered : undefined;
    tvFocusRegistry.restore(detail.value ? (exists && remembered ? remembered
      : activeTab.value === 'songs' && songsPage.value.tracks.length ? 'detail-primary' : `artist-tab-${activeTab.value}`) : 'artist-retry');
  }
  if (detail.value && !childStatus.value) void loadChildPage();
}

onMounted(() => {
  pageActive = true;
  watch(id, (newId, oldId) => {
    if (route.name !== 'artist-detail') return;
    if (oldId) rememberPageFocus(oldId);
    const remembered = recallFocus(pageKey(newId)) ?? '';
    activeTab.value = remembered.startsWith('artist-albums-') || remembered === 'artist-tab-albums' ? 'albums'
      : remembered.startsWith('artist-mv-') || remembered === 'artist-tab-mv' ? 'mv' : 'songs';
    menuTrack.value = null;
    releaseBackLayer?.();
    releaseBackLayer = backLayers.register(
      { kind: 'page', close: () => void router.back() }, String(route.query.sourceFocusId ?? ''),
    );
    void loadPage(true);
  }, { immediate: true });
});

// Capture while the route still belongs to this artist, not the destination's ID.
onBeforeRouteLeave(() => { rememberPageFocus(id.value); });

onBeforeUnmount(() => {
  pageActive = false;
  loadGeneration += 1;
  releaseBackLayer?.();
  releaseBackLayer = undefined;
});
</script>

<template>
  <section ref="pageElement" class="detail-page" data-page="artist-detail" :data-default-focus-id="defaultFocusId">
    <DetailHero
      v-if="detail && detail.kind === 'artist'"
      :title="detail.title"
      :artwork="detail.artwork"
      subtitle="歌手"
      compact
      :meta="detail.summary || ''"
      :primary-label="`播放已加载的 ${songsPage.tracks.length} 首`"
      :hide-primary="!songsPage.tracks.length"
      :favorite="isFavorite"
      :favorite-label="isFavorite ? '已关注 · 取消' : '关注歌手'"
      :hide-share="true"
      primary-down-focus-id="artist-tab-songs"
      @primary="playAll"
      @favorite="toggleFavorite"
    />
    <section v-else class="detail-state" aria-live="polite">
      <h2>{{ loading ? '正在加载歌手详情' : '详情加载失败' }}</h2>
      <p>请检查网络后重试，也可以按返回键回到来源页面。</p>
      <button v-tv-focus type="button" data-focus-id="artist-retry" data-focus-left="nav-home"
        :aria-busy="loading" @click="loadPage()">{{ loading ? '正在加载…' : '重新加载' }}</button>
    </section>

    <div v-if="detail" class="tabs" role="tablist">
      <button
        v-for="tab in tabs"
        :key="tab"
        v-tv-focus
        type="button"
        role="tab"
        :aria-selected="activeTab === tab"
        class="tab"
        :class="{ active: activeTab === tab }"
        :data-focus-id="`artist-tab-${tab}`"
        :data-focus-down="firstContentFocusId"
        @click="switchTab(tab)"
      >{{ { songs: '热门歌曲', albums: '专辑', mv: 'MV' }[tab] }}</button>
    </div>

    <template v-if="detail && activeTab === 'songs'">
      <SongList
        v-if="songsPage.tracks.length"
        :tracks="songsPage.tracks"
        group="artist"
        up-focus-id="artist-tab-songs"
        :down-focus-id="childStatus === 'loading' || childStatus === 'error' || currentPage().nextCursor ? 'artist-page-action' : undefined"
        @play="playTrack"
        @menu="openMenu"
      />
      <p v-else-if="childStatus === 'ready'" class="empty">暂无歌曲</p>
    </template>

    <template v-else-if="detail && (activeTab === 'albums' || activeTab === 'mv')">
      <ContentGrid
        v-if="currentPage().items.length"
        :cards="currentPage().items"
        :group="`artist-${activeTab}`"
        :up-focus-id="`artist-tab-${activeTab}`"
        :down-focus-id="childStatus === 'loading' || childStatus === 'error' || currentPage().nextCursor ? 'artist-page-action' : undefined"
        @select="(card) => openCard(card)"
      />
      <p v-else-if="childStatus === 'ready'" class="empty">暂无{{ activeTab === 'albums' ? '专辑' : 'MV' }}</p>
    </template>
      <div v-if="detail" class="pagination-status" aria-live="polite">
        <button v-if="childStatus === 'loading' || childStatus === 'error' || currentPage().nextCursor || retainAction"
          v-tv-focus type="button" data-focus-id="artist-page-action" data-focus-left="nav-home"
          :data-focus-up="lastContentFocusId"
          @focus="childStatus === 'loading' && !contentFocusIds.length && beginPaginationFocus($event.target as HTMLElement)"
          :aria-busy="childStatus === 'loading'" @click="loadChildPage($event)">
          {{ childStatus === 'loading' ? '加载中…' : childStatus === 'error' ? '加载失败，按确认重试' : '加载更多' }}
        </button>
        <span v-else-if="childStatus === 'ready' && (currentPage().items.length || currentPage().tracks.length)" class="note">已全部加载</span>
      </div>

    <p v-if="notice" class="notice" role="status">{{ notice }}</p>

    <SongActions
      :track="menuTrack"
      :source-focus-id="menuSourceFocusId"
      @close="menuTrack = null"
    />
  </section>
</template>

<style scoped>
.detail-page { padding: 4px 14px 150px; min-width: 0; }
.tabs { display: flex; gap: 10px; margin: 22px 0 18px; }
.tabs .tab { min-width: 110px; height: 48px; border: 1px solid transparent; border-radius: 24px; color: var(--tv-text-secondary); font-size: 16px; background: rgba(255, 255, 255, 0.05); }
.tabs .tab.active { color: white; background: rgba(0, 113, 227, 0.4); }
.empty { padding: 30px 8px; color: var(--tv-text-secondary); font-size: 16px; }
.detail-state { min-height: 300px; padding: 30px; border-radius: var(--tv-radius-panel); background: rgba(255, 255, 255, 0.05); }
.detail-state h2 { font-size: 28px; }
.detail-state p, .note { color: var(--tv-text-secondary); font-size: 17px; }
.detail-state button, .pagination-status button { min-width: 220px; height: 52px; border: 1px solid rgba(124, 188, 255, 0.35); border-radius: 26px; color: #dceeff; font-size: 17px; background: rgba(36, 102, 164, 0.3); }
.pagination-status { display: flex; justify-content: center; padding: 22px 0 8px; }
.notice { position: fixed; left: 50%; bottom: 40px; z-index: 20; padding: 12px 24px; border-radius: 18px; transform: translateX(-50%); color: #fff; font-size: 15px; background: rgba(20, 90, 160, 0.95); }
</style>
