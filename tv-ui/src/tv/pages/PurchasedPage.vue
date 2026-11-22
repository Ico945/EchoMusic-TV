<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import ContentGrid from '../content/ContentGrid.vue';
import PaginationStatus from '../content/PaginationStatus.vue';
import SongList from '../content/SongList.vue';
import SongActions from '../content/SongActions.vue';
import { recallFocus, rememberFocus, sourceFocusFromEvent } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import type { LibrarySectionKey, TvContentCard } from '../native/contentTypes';
import { useTvAppStore, type TvTrack } from '../stores/app';
import { useTvLibraryStore, type LibrarySectionState } from '../stores/library';
import { useTvPlayerStore } from '../stores/player';

const menuTrack = ref<TvTrack | null>(null);
const PAGE_KEY = 'library-purchased';
const app = useTvAppStore();
const library = useTvLibraryStore();
const player = useTvPlayerStore();
const router = useRouter();

const tabs = [
  { id: 'songs', label: '单曲', section: 'purchasedSongs' },
  { id: 'albums', label: '专辑', section: 'purchasedAlbums' },
] as const;

const activeTab = ref<(typeof tabs)[number]['id']>('songs');
const loadingMore = ref(false);

const loggedIn = computed(() => app.session.status === 'loggedIn');

const activeSectionKey = computed<LibrarySectionKey>(() => tabs.find((tab) => tab.id === activeTab.value)?.section ?? 'purchasedSongs');
const section = computed<LibrarySectionState>(() => library.sections[activeSectionKey.value] ?? { items: [], nextCursor: null, total: null, status: 'loading' });

const songTracks = computed<TvTrack[]>(() =>
  activeSectionKey.value === 'purchasedSongs'
    ? section.value.items
        .filter((card) => card.kind === 'song')
        .map((card) => ({ id: card.id, title: card.title, artist: card.subtitle, album: '', artwork: card.artwork, durationMs: 0 }))
    : [],
);

const trackIds = computed(() => songTracks.value.map((track) => track.id));

function playTrack(track: TvTrack): void {
  const index = Math.max(0, songTracks.value.findIndex((item) => item.id === track.id));
  void player.playQueue(trackIds.value, index).catch(() => undefined);
}

function openAlbum(card: TvContentCard, event?: Event): void {
  const focusId = event ? sourceFocusFromEvent(event) : undefined;
  void router.push({ name: 'album-detail', params: { id: card.id }, ...(focusId ? { query: { sourceFocusId: focusId } } : {}) });
}

function switchTab(tab: (typeof tabs)[number]): void {
  activeTab.value = tab.id;
  if (library.sections[tab.section] === undefined) void library.loadSection(tab.section);
  void nextTick(() => tvFocusRegistry.restore(`purchased-tab-${tab.id}`));
}

async function loadMore(): Promise<void> {
  const cursor = section.value.nextCursor;
  if (!cursor || loadingMore.value) return;
  loadingMore.value = true;
  await library.loadSection(activeSectionKey.value, cursor);
  loadingMore.value = false;
}

function requestLogin(): void {
  window.dispatchEvent(new CustomEvent('echo-tv-login-requested'));
}

watch([loggedIn, () => app.session.displayName, activeSectionKey], ([signedIn]) => {
  if (signedIn && library.sections[activeSectionKey.value] === undefined) void library.loadSection(activeSectionKey.value);
}, { immediate: true });

onMounted(() => {
  void nextTick(() => tvFocusRegistry.restore(recallFocus(PAGE_KEY) ?? 'purchased-tab-songs'));
});

onBeforeUnmount(() => rememberFocus(PAGE_KEY));
</script>

<template>
  <section class="purchased-page" data-page="library-purchased">
    <header class="page-header">
      <span class="eyebrow">LIBRARY</span>
      <h1>已购</h1>
      <p>已购买的歌曲与专辑。</p>
    </header>

    <div v-if="!loggedIn" class="login-prompt">
      <p>登录后即可查看已购内容</p>
      <button v-tv-focus type="button" data-focus-id="purchased-login" @click="requestLogin">立即登录</button>
    </div>

    <template v-else>
      <div class="tab-bar" role="tablist" aria-label="已购类别">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          v-tv-focus
          type="button"
          role="tab"
          :aria-selected="activeTab === tab.id"
          class="tab"
          :class="{ active: activeTab === tab.id }"
          :data-purchased-tab="tab.id"
          :data-focus-id="`purchased-tab-${tab.id}`"
          @click="switchTab(tab)"
        >{{ tab.label }}</button>
      </div>

      <template v-if="activeSectionKey === 'purchasedSongs'">
        <SongList
          v-if="songTracks.length"
          :tracks="songTracks"
          group="purchased-songs"
          :down-focus-id="section.status === 'error' ? 'pagination-retry' : section.nextCursor || section.status === 'loading' ? 'pagination-load-more' : undefined"
          @play="playTrack"
          @menu="menuTrack = $event"
        />
        <p v-else-if="section.status === 'ready'" class="empty">暂无已购单曲</p>
      </template>
      <template v-else>
        <ContentGrid
          v-if="section.items.length"
          :cards="section.items"
          group="purchased-albums"
          :down-focus-id="section.status === 'error' ? 'pagination-retry' : section.nextCursor || section.status === 'loading' ? 'pagination-load-more' : undefined"
          @select="(card, event) => openAlbum(card, event)"
        />
        <p v-else-if="section.status === 'ready'" class="empty">暂无已购专辑</p>
      </template>

      <p v-if="section.status === 'error'" class="empty">加载失败，请重试</p>
      <button v-if="section.status === 'error' && !section.items.length" v-tv-focus type="button" data-focus-id="purchased-retry" @click="library.loadSection(activeSectionKey)">重新加载</button>

      <div v-if="section.status === 'loading'" class="skeleton" aria-label="加载中" />

      <PaginationStatus
        v-if="section.items.length"
        :item-focus-ids="activeTab === 'songs' ? songTracks.map(track => `purchased-songs-${track.id}`) : section.items.map((_, index) => `purchased-${activeTab}-${index}`)"
        :focus-context="activeTab" left-focus-id="nav-library"
        :loading="section.status === 'loading'"
        :has-more="Boolean(section.nextCursor)"
        :error="section.status === 'error'"
        @load-more="loadMore"
        @retry="loadMore"
      />
    </template>
    <SongActions :track="menuTrack" :source-focus-id="menuTrack ? 'purchased-songs-' + menuTrack.id : undefined" @close="menuTrack = null" />
  </section>
</template>

<style scoped>
.purchased-page { padding: 4px 14px 150px; min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size: 44px; letter-spacing: -1.6px; }
.page-header p { margin: 10px 0 0; color: var(--tv-text-secondary); font-size: 17px; }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.tab-bar { display: flex; gap: 10px; margin: 22px 0 18px; }
.tab-bar .tab { min-width: 110px; height: 48px; border: 1px solid transparent; border-radius: 24px; color: var(--tv-text-secondary); font-size: 16px; background: rgba(255, 255, 255, 0.05); }
.tab-bar .tab.active { color: white; background: rgba(0, 113, 227, 0.4); }
.empty { padding: 30px 8px; color: var(--tv-text-secondary); font-size: 16px; }
.login-prompt { padding: 30px 8px; }
.login-prompt p { color: var(--tv-text-secondary); font-size: 16px; }
.login-prompt button { min-width: 220px; height: 52px; border: 1px solid rgba(124, 188, 255, 0.35); border-radius: 26px; color: #dceeff; background: rgba(36, 102, 164, 0.3); }
.skeleton { min-height: 200px; border-radius: var(--tv-radius-panel); background: rgba(255, 255, 255, 0.05); }
</style>
