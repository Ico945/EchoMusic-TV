<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import CommentList from '../content/CommentList.vue';
import ContentGrid from '../content/ContentGrid.vue';
import DetailHero from '../content/DetailHero.vue';
import { useTransientNotice } from '../content/notice';
import { tvFocusRegistry } from '../focus/directive';
import { useBackLayerRegistry } from '../input/backCoordinator';
import type { TvContentCard } from '../native/contentTypes';
import { useTvDetailStore } from '../stores/detail';
import { useTvPlayerStore } from '../stores/player';
import ShareDialog from '../overlays/ShareDialog.vue';

const route = useRoute();
const router = useRouter();
const detailStore = useTvDetailStore();
const player = useTvPlayerStore();
const backLayers = useBackLayerRegistry();
const { notice, show } = useTransientNotice();

const kind = 'song';
const id = computed(() => String(route.params.id ?? ''));
const tabs = ['info', 'comments'] as const;
const activeTab = ref<(typeof tabs)[number]>('info');
let releaseBackLayer: (() => void) | undefined;
let pageActive = false;
let loadGeneration = 0;
const loading = ref(false);
const shareOpen = ref(false);

const detail = computed(() => detailStore.detail);
const durationLabel = computed(() => {
  const track = detail.value?.kind === 'song' ? detail.value.track : null;
  const nativeDuration = track && player.currentTrack?.id === track.id ? player.durationMs : 0;
  const duration = Number.isFinite(nativeDuration) && nativeDuration > 0 ? nativeDuration : track?.durationMs ?? 0;
  if (!Number.isFinite(duration) || duration < 1000) return '时长暂不可用';
  const seconds = Math.floor(duration / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
});
const relatedPage = computed(() => {
  const value = detail.value;
  if (value && value.kind === 'song') return value.related;
  return { items: [], nextCursor: null, total: null };
});
const commentPage = computed(() => detailStore.comments);
const isFavorite = computed(() => detailStore.favoriteState('song', id.value) ?? false);

function play(): void {
  const track = detail.value?.kind === 'song' ? detail.value.track : null;
  if (!track) { show('当前详情缺少可用音源，请从歌曲列表重新进入'); return; }
  void player.playQueue([track.id], 0).catch(() => undefined);
}

function toggleFavorite(): void {
  if (!detail.value) return;
  const next = !isFavorite.value;
  const targetId = id.value;
  void detailStore.toggleFavorite('song', targetId, next).then((ok) => {
    if (pageActive && targetId === id.value) show(ok ? (next ? '已收藏' : '已取消收藏') : '操作未成功，请重试');
  });
}

function openCard(card: TvContentCard): void {
  const routes: Record<string, string> = { playlist: 'playlist-detail', album: 'album-detail', artist: 'artist-detail', song: 'song-detail', mv: 'mv-detail' };
  const name = routes[card.kind];
  if (!name) return;
  const focusId = (document.activeElement as HTMLElement | null)?.dataset.focusId;
  void router.push({ name, params: { id: card.id }, ...(focusId ? { query: { sourceFocusId: focusId } } : {}) });
}

function switchTab(tab: (typeof tabs)[number]): void {
  activeTab.value = tab;
  if (tab === 'comments' && detailStore.commentsStatus === 'idle') void detailStore.loadComments(kind, id.value);
}

async function load(initial = false): Promise<void> {
  if (!initial && loading.value) return;
  const generation = ++loadGeneration;
  loading.value = true;
  const previousFocus = document.activeElement;
  const pending = detailStore.loadDetail(kind, id.value);
  await nextTick();
  if (!pageActive || generation !== loadGeneration) return;
  if (initial && document.activeElement === previousFocus) tvFocusRegistry.restore('song-retry');
  await pending;
  if (!pageActive || generation !== loadGeneration) return;
  const ownsFocus = (document.activeElement as HTMLElement | null)?.dataset.focusId === 'song-retry';
  loading.value = false;
  await nextTick();
  if (pageActive && generation === loadGeneration && ownsFocus) {
    tvFocusRegistry.restore(detailStore.status === 'ready' ? 'detail-primary' : 'song-retry');
  }
}

onMounted(() => {
  pageActive = true;
  // Back must work even before the network response arrives.
  releaseBackLayer = backLayers.register(
    { kind: 'page', close: () => void router.back() }, String(route.query.sourceFocusId ?? ''),
  );
  watch(id, () => { activeTab.value = 'info'; void load(true); }, { immediate: true });
});

onBeforeUnmount(() => {
  pageActive = false;
  loadGeneration += 1;
  releaseBackLayer?.();
  releaseBackLayer = undefined;
});
</script>

<template>
  <section class="detail-page" data-page="song-detail">
    <DetailHero
      v-if="!loading && detail && detail.kind === 'song'"
      :title="detail.title"
      :artwork="detail.artwork || detail.track?.artwork"
      :subtitle="detail.track ? `${detail.track.artist} · ${detail.track.album}` : '单曲'"
      :meta="durationLabel"
      :primary-label="detail.track ? '播放' : '音源暂不可用'"
      @primary="play"
      :favorite="isFavorite"
      :favorite-known="detailStore.favoriteState('song', id) !== undefined"
      @favorite="toggleFavorite"
      @share="shareOpen = true"
    />
    <section v-else class="detail-state" aria-live="polite">
      <h2>{{ loading ? '正在加载歌曲详情' : '详情加载失败' }}</h2>
      <p>请检查网络后重试，也可以按返回键回到歌曲列表。</p>
      <button v-tv-focus type="button" data-focus-id="song-retry" data-focus-left="nav-home"
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
        :data-focus-id="`song-tab-${tab}`"
        @click="switchTab(tab)"
      >{{ { info: '信息', comments: '评论' }[tab] }}</button>
    </div>

    <template v-if="detail && activeTab === 'info'">
      <ContentGrid
        v-if="relatedPage.items.length"
        :cards="relatedPage.items"
        group="song-related"
        @select="(card) => openCard(card)"
      />
      <p v-else class="empty">暂无相关推荐</p>
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

    <p v-if="notice" class="notice" role="status">{{ notice }}</p>

    <ShareDialog
      v-if="shareOpen"
      kind="song"
      :id="id"
      :title="detail?.title"
      source-focus-id="detail-share"
      @close="shareOpen = false"
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
.skeleton { min-height: 300px; border-radius: var(--tv-radius-panel); background: rgba(255, 255, 255, 0.05); }
.notice { position: fixed; left: 50%; bottom: 40px; z-index: 20; padding: 12px 24px; border-radius: 18px; transform: translateX(-50%); color: #fff; font-size: 15px; background: rgba(20, 90, 160, 0.95); }
</style>
