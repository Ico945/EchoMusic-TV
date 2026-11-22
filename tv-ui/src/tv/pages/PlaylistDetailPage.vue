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
import { mayAutoRestorePageFocus, recallFocus, rememberFocus } from '../content/routeMemory';
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

const PAGE_KEY = 'playlist-detail';
const kind = 'playlist';
const id = computed(() => String(route.params.id ?? ''));
const personalPlaylist = computed(() => id.value.replace(/^playlist:/, '').startsWith('library_'));
const tabs = ['songs', 'comments', 'introduction'] as const;
const activeTab = ref<(typeof tabs)[number]>('songs');
const menuTrack = ref<TvTrack | null>(null);
const menuSourceFocusId = ref<string | undefined>(undefined);
let releaseBackLayer: (() => void) | undefined;
let pageActive = false;
let loadGeneration = 0;
const loading = ref(false);
const shareTarget = ref<{ kind: 'song' | 'playlist'; id: string; title: string; sourceFocusId: string } | null>(null);

const detail = computed(() => detailStore.detail);
const childKey = computed(() => detailChildKey(kind, id.value, 'songs'));
const trackPage = computed(() => detailStore.children[childKey.value] ?? { items: [], tracks: [], nextCursor: null, total: null });
const trackStatus = computed(() => detailStore.childrenStatus[childKey.value] ?? 'idle');
const commentPage = computed(() => detailStore.comments);
const isFavorite = computed(() => detailStore.favoriteState(kind, id.value) ?? false);
const trackCountLabel = computed(() => {
  const total = detail.value?.kind === 'playlist' ? detail.value.trackCount ?? trackPage.value.total : null;
  if (typeof total === 'number') return `${total} 首`;
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
  void detailStore.toggleFavorite('playlist', targetId, next).then((ok) => {
    if (pageActive && targetId === id.value) show(ok ? (next ? '已收藏' : '已取消收藏') : '操作未成功，请重试');
  });
}

function onShare(): void {
  if (!detail.value) return;
  shareTarget.value = { kind: 'playlist', id: id.value, title: detail.value.title, sourceFocusId: 'detail-share' };
}

function switchTab(tab: (typeof tabs)[number]): void {
  activeTab.value = tab;
  if (tab === 'comments' && detailStore.commentsStatus === 'idle') void detailStore.loadComments(kind, id.value);
  void nextTick(() => {
    tvFocusRegistry.restore(`playlist-tab-${tab}`);
  });
}

function loadMoreTracks(): void {
  const cursor = trackPage.value.nextCursor;
  if (cursor) void detailStore.loadChildren(kind, id.value, 'songs', cursor);
}

function retryTracks(): void {
  void detailStore.loadChildren(kind, id.value, 'songs', trackPage.value.nextCursor ?? undefined);
}

function loadMoreComments(): void {
  const cursor = commentPage.value.nextCursor;
  void detailStore.loadComments(kind, id.value, cursor ?? undefined);
}

function openMenu(track: TvTrack): void {
  menuTrack.value = track;
  menuSourceFocusId.value = `playlist-${track.id}`;
}

async function loadPage(initial = false): Promise<void> {
  if (!initial && loading.value) return;
  const generation = ++loadGeneration;
  loading.value = true;
  const previousFocus = document.activeElement;
  const pending = detailStore.loadDetail(kind, id.value);
  await nextTick();
  if (!pageActive || generation !== loadGeneration) return;
  const ownsInitialFocus = initial && document.activeElement === previousFocus;
  // An immediate response may render the real hero before the loading button
  // ever exists. Never restore a missing placeholder (registry falls to nav).
  if (ownsInitialFocus && detailStore.status !== 'ready') tvFocusRegistry.restore('playlist-retry');
  const initialFocusTarget = ownsInitialFocus ? document.activeElement : null;
  await pending;
  if (!pageActive || generation !== loadGeneration) return;
  const ownsRetryFocus = (document.activeElement as HTMLElement | null)?.dataset.focusId === 'playlist-retry';
  loading.value = false;
  await nextTick();
  if (!pageActive || generation !== loadGeneration) return;
  if (ownsRetryFocus || (initialFocusTarget !== null && document.activeElement === initialFocusTarget) ||
      (initial && mayAutoRestorePageFocus(personalPlaylist.value ? 'nav-library' : 'nav-home'))) {
    const remembered = recallFocus(`${PAGE_KEY}:${id.value}`);
    const exists = remembered && Array.from(document.querySelectorAll<HTMLElement>('[data-focus-id]')).some(el => el.dataset.focusId === remembered);
    tvFocusRegistry.restore(detailStore.status === 'ready' ? exists ? remembered : 'detail-primary' : 'playlist-retry');
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
  watch(id, () => { activeTab.value = 'songs'; void loadPage(true); }, { immediate: true });
});

onBeforeUnmount(() => {
  pageActive = false;
  loadGeneration += 1;
  rememberFocus(`${PAGE_KEY}:${id.value}`);
  releaseBackLayer?.();
  releaseBackLayer = undefined;
});
</script>

<template>
  <section class="detail-page" data-page="playlist-detail" :data-default-focus-id="detail ? 'detail-primary' : 'playlist-retry'">
    <DetailHero
      v-if="detail && detail.kind === 'playlist'"
      :title="personalPlaylist && typeof route.query.title === 'string' ? route.query.title : detail.title"
      :hide-social-actions="personalPlaylist"
      :primary-down-focus-id="personalPlaylist && trackPage.tracks.length ? 'playlist-' + trackPage.tracks[0].id : 'playlist-tab-songs'"
      :primary-left-focus-id="personalPlaylist ? 'nav-library' : undefined"
      :artwork="personalPlaylist && typeof route.query.artwork === 'string' && route.query.artwork.startsWith('https://') ? route.query.artwork : detail.artwork"
      :subtitle="personalPlaylist ? '来自你的酷狗资料库' : detail.creator ? `by ${detail.creator.nickname}` : '精选歌单'"
      :meta="personalPlaylist ? trackCountLabel : `${detail.playCount} 次播放 · ${trackCountLabel} · ${detail.tags.join(' / ')}`"
      :primary-label="trackPage.nextCursor || (trackPage.total ?? 0) > trackPage.tracks.length ? `播放已加载的 ${trackPage.tracks.length} 首` : '播放全部'"
      @primary="playAll"
      :primary-disabled="!trackPage.tracks.length"
      :favorite="isFavorite"
      :favorite-known="detailStore.favoriteState('playlist', id) !== undefined"
      @favorite="toggleFavorite"
      @share="onShare"
    />
    <section v-else class="detail-state" aria-live="polite">
      <h2>{{ loading ? '正在加载歌单详情' : '详情加载失败' }}</h2>
      <p>请检查网络后重试，也可以按返回键回到歌单列表。</p>
      <button v-tv-focus type="button" data-focus-id="playlist-retry" data-focus-left="nav-home"
        :aria-busy="loading" @click="loadPage()">{{ loading ? '正在加载…' : '重新加载' }}</button>
    </section>

    <div v-if="detail && !personalPlaylist" class="tabs" role="tablist">
      <button
        v-for="tab in (personalPlaylist ? tabs.filter(item => item === 'songs') : tabs)"
        :key="tab"
        v-tv-focus
        type="button"
        role="tab"
        :aria-selected="activeTab === tab"
        class="tab"
        :class="{ active: activeTab === tab }"
        :data-focus-id="`playlist-tab-${tab}`"
        @click="switchTab(tab)"
      >{{ { songs: '歌曲', comments: '评论', introduction: '介绍' }[tab] }}</button>
    </div>

    <template v-if="detail && activeTab === 'songs'">
      <SongList
        v-if="trackPage.tracks.length"
        :tracks="trackPage.tracks"
        group="playlist"
        :down-focus-id="trackStatus === 'error' ? 'pagination-retry' : trackStatus === 'loading' || trackPage.nextCursor ? 'pagination-load-more' : undefined"
        :up-focus-id="personalPlaylist ? 'detail-primary' : 'playlist-tab-songs'"
        :left-focus-id="personalPlaylist ? 'nav-library' : undefined"
        @play="playTrack"
        @menu="openMenu"
      />
      <p v-else-if="trackStatus === 'ready'" class="empty">歌单暂无歌曲</p>
      <PaginationStatus
        :focus-context="id"
        :item-focus-ids="trackPage.tracks.map(track => `playlist-${track.id}`)"
        up-focus-id="detail-primary"
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
        @load-more="loadMoreComments"
        @retry="loadMoreComments"
      />
    </template>

    <p v-else-if="detail && activeTab === 'introduction'" class="introduction" v-tv-focus data-focus-id="detail-introduction" tabindex="0" aria-label="介绍，上下键阅读" @keydown="scrollReadingPanel">{{ detail.kind === 'playlist' ? detail.introduction || '暂无介绍' : '' }}</p>

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
