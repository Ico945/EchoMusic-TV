<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { scrollReadingPanel } from '../content/reading';
import CommentList from '../content/CommentList.vue';
import DetailHero from '../content/DetailHero.vue';
import PaginationStatus from '../content/PaginationStatus.vue';
import SongActions from '../content/SongActions.vue';
import SongList from '../content/SongList.vue';
import { useTransientNotice } from '../content/notice';
import { recallFocus, rememberFocus } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import { useBackLayerRegistry } from '../input/backCoordinator';
import type { TvTrack } from '../stores/app';
import { detailChildKey, detailContentKey, useTvDetailStore } from '../stores/detail';
import { useTvPlayerStore } from '../stores/player';
import ShareDialog from '../overlays/ShareDialog.vue';

const route = useRoute();
const router = useRouter();
const detailStore = useTvDetailStore();
const player = useTvPlayerStore();
const backLayers = useBackLayerRegistry();
const { notice, show } = useTransientNotice();

const PAGE_KEY = 'album-detail';
const kind = 'album';
const id = computed(() => String(route.params.id ?? ''));
const tabs = ['songs', 'comments', 'introduction'] as const;
const activeTab = ref<(typeof tabs)[number]>('songs');
const menuTrack = ref<TvTrack | null>(null);
const menuSourceFocusId = ref<string | undefined>(undefined);
let releaseBackLayer: (() => void) | undefined;
let pageActive = false;
let loadGeneration = 0;
const loading = ref(false);
const shareTarget = ref<{ kind: 'song' | 'album'; id: string; title: string; sourceFocusId: string } | null>(null);

const detail = computed(() => detailStore.detail);
const childKey = computed(() => detailChildKey(kind, id.value, 'songs'));
const trackPage = computed(() => detailStore.children[childKey.value] ?? { items: [], tracks: [], nextCursor: null, total: null });
const trackStatus = computed(() => detailStore.childrenStatus[childKey.value] ?? 'idle');
const commentPage = computed(() => detailStore.comments);
const isFavorite = computed(() => detailStore.favoriteState(kind, id.value) ?? false);
const trackCountLabel = computed(() => {
  const total = trackPage.value.total;
  if (total !== null) return `${total} 首`;
  if (trackPage.value.tracks.length) return `${trackPage.value.tracks.length} 首`;
  return trackStatus.value === 'ready' ? '0 首' : '歌曲数加载中';
});

const trackIds = computed(() => trackPage.value.tracks.map((track) => track.id));

function playAll(): void {
  if (!trackIds.value.length) return;
  void player.playQueue(trackIds.value, 0).catch(() => undefined);
}

function playTrack(track: TvTrack): void {
  void player.playQueue(trackIds.value, Math.max(0, trackPage.value.tracks.findIndex((item) => item.id === track.id))).catch(() => undefined);
}

function toggleFavorite(): void {
  if (!detail.value) return;
  const next = !isFavorite.value;
  const targetId = id.value;
  void detailStore.toggleFavorite('album', targetId, next).then((ok) => {
    if (pageActive && targetId === id.value) show(ok ? (next ? '已收藏' : '已取消收藏') : '操作未成功，请重试');
  });
}

function shareAlbum(): void {
  if (!detail.value) return;
  shareTarget.value = { kind: 'album', id: id.value, title: detail.value.title, sourceFocusId: 'detail-share' };
}

function switchTab(tab: (typeof tabs)[number]): void {
  activeTab.value = tab;
  if (tab === 'comments' && detailStore.commentsStatus === 'idle') void detailStore.loadComments(kind, id.value);
}

function loadMoreTracks(): void {
  const cursor = trackPage.value.nextCursor;
  if (cursor) void detailStore.loadChildren(kind, id.value, 'songs', cursor);
}

function retryTracks(): void {
  void detailStore.loadChildren(kind, id.value, 'songs', trackPage.value.nextCursor ?? undefined);
}

function openMenu(track: TvTrack): void {
  menuTrack.value = track;
  menuSourceFocusId.value = `album-${track.id}`;
}

async function load(initial = false): Promise<void> {
  if (!initial && loading.value) return;
  const generation = ++loadGeneration;
  loading.value = true;
  const previousFocus = document.activeElement;
  const pending = detailStore.loadDetail(kind, id.value);
  await nextTick();
  if (!pageActive || generation !== loadGeneration) return;
  if (initial && document.activeElement === previousFocus) tvFocusRegistry.restore('album-retry');
  await pending;
  if (!pageActive || generation !== loadGeneration) return;
  const ownsRetryFocus = (document.activeElement as HTMLElement | null)?.dataset.focusId === 'album-retry';
  loading.value = false;
  await nextTick();
  if (!pageActive || generation !== loadGeneration) return;
  if (ownsRetryFocus || initial) {
    tvFocusRegistry.restore(detailStore.status === 'ready' ? recallFocus(PAGE_KEY) ?? 'detail-primary' : 'album-retry');
  }
  if (detailStore.status === 'ready' && trackStatus.value !== 'ready' && trackStatus.value !== 'loading') {
    void detailStore.loadChildren(kind, id.value, 'songs');
  }
}

onMounted(() => {
  pageActive = true;
  releaseBackLayer = backLayers.register(
    { kind: 'page', close: () => void router.back() },
    String(route.query.sourceFocusId ?? ''),
  );
  watch(id, () => { activeTab.value = 'songs'; void load(true); }, { immediate: true });
});

onBeforeUnmount(() => {
  pageActive = false;
  loadGeneration += 1;
  rememberFocus(PAGE_KEY);
  releaseBackLayer?.();
  releaseBackLayer = undefined;
});
</script>

<template>
  <section class="detail-page" data-page="album-detail">
    <DetailHero
      v-if="detail && detail.kind === 'album'"
      :title="detail.title"
      :artwork="detail.artwork"
      :subtitle="detail.artist || '专辑'"
      :meta="`${detail.publishDate || ''} · ${trackCountLabel}`"
      :primary-label="trackPage.nextCursor || (trackPage.total ?? 0) > trackPage.tracks.length ? `播放已加载的 ${trackPage.tracks.length} 首` : '播放全部'"
      @primary="playAll"
      :favorite="isFavorite"
      :favorite-known="detailStore.favoriteState('album', id) !== undefined"
      :primary-disabled="!trackPage.tracks.length"
      @favorite="toggleFavorite"
      @share="shareAlbum"
    />
    <section v-else class="detail-state" aria-live="polite">
      <h2>{{ loading ? '正在加载专辑详情' : '详情加载失败' }}</h2>
      <p>请检查网络后重试，也可以按返回键回到专辑列表。</p>
      <button v-tv-focus type="button" data-focus-id="album-retry" data-focus-left="nav-home"
        :aria-busy="loading" @click="load()">{{ loading ? '正在加载…' : '重新加载' }}</button>
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
        :data-focus-id="`album-tab-${tab}`"
        @click="switchTab(tab)"
      >{{ { songs: '歌曲', comments: '评论', introduction: '介绍' }[tab] }}</button>
    </div>

    <template v-if="detail && activeTab === 'songs'">
      <SongList
        v-if="trackPage.tracks.length"
        :tracks="trackPage.tracks"
        group="album"
        :down-focus-id="trackStatus === 'error' ? 'pagination-retry' : trackStatus === 'loading' || trackPage.nextCursor ? 'pagination-load-more' : undefined"
        @play="playTrack"
        @menu="openMenu"
      />
      <p v-else-if="trackStatus === 'ready'" class="empty">专辑暂无歌曲</p>
      <PaginationStatus
        :focus-context="id"
        :item-focus-ids="trackPage.tracks.map(track => `album-${track.id}`)"
        up-focus-id="album-tab-songs"
        :loading="trackStatus === 'loading'"
        :has-more="Boolean(trackPage.nextCursor)"
        :error="trackStatus === 'error'"
        @load-more="loadMoreTracks"
        @retry="retryTracks"
      />
    </template>

    <template v-else-if="detail && activeTab === 'comments'">
      <CommentList
        :key="id"
        :comments="commentPage.items"
        :loading="detailStore.commentsStatus === 'loading'"
        :has-more="Boolean(commentPage.nextCursor)"
        :error="detailStore.commentsStatus === 'error'"
        @load-more="detailStore.loadComments(kind, id, commentPage.nextCursor ?? undefined)"
        @retry="detailStore.loadComments(kind, id)"
      />
    </template>

    <p v-else-if="detail && activeTab === 'introduction'" class="introduction" v-tv-focus data-focus-id="detail-introduction" tabindex="0" aria-label="介绍，上下键阅读" @keydown="scrollReadingPanel">{{ detail.kind === 'album' ? detail.introduction || '暂无介绍' : '' }}</p>

    <p v-if="notice" class="notice" role="status">{{ notice }}</p>

    <SongActions
      :track="menuTrack"
      :source-focus-id="menuSourceFocusId"
      @close="menuTrack = null"
    />

    <ShareDialog
      v-if="shareTarget"
      :kind="shareTarget.kind"
      :id="shareTarget.id"
      :title="shareTarget.title"
      :source-focus-id="shareTarget.sourceFocusId"
      @close="shareTarget = null"
    />
  </section>
</template>

<style scoped>
.detail-page { padding: 4px 14px 150px; min-width: 0; }
.detail-state { padding: 42px; min-height: 240px; border-radius: var(--tv-radius-panel); background: rgba(255,255,255,.05); }
.detail-state h2 { font-size: 32px; }.detail-state p { color: var(--tv-text-secondary); font-size: 20px; }
.detail-state button { min-width: 180px; height: 56px; border: 0; border-radius: 28px; font-size: 20px; color: white; background: #174a80; }
.tabs { display: flex; gap: 10px; margin: 22px 0 18px; }
.tabs .tab { min-width: 110px; height: 48px; border: 1px solid transparent; border-radius: 24px; color: var(--tv-text-secondary); font-size: 16px; background: rgba(255, 255, 255, 0.05); }
.tabs .tab.active { color: white; background: rgba(0, 113, 227, 0.4); }
.empty { padding: 30px 8px; color: var(--tv-text-secondary); font-size: 16px; }
.introduction { padding: 20px 10px; color: var(--tv-text); font-size: 17px; line-height: 1.8; }
.notice { position: fixed; left: 50%; bottom: 40px; z-index: 20; padding: 12px 24px; border-radius: 18px; transform: translateX(-50%); color: #fff; font-size: 15px; background: rgba(20, 90, 160, 0.95); }
.introduction { max-height:480px; overflow:auto; font-size:24px; padding:24px; --tv-focus-scale:1; }
</style>
