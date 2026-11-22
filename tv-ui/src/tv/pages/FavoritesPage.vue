<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import ContentGrid from '../content/ContentGrid.vue';
import ContentSkeleton from '../content/ContentSkeleton.vue';
import PaginationStatus from '../content/PaginationStatus.vue';
import SongActions from '../content/SongActions.vue';
import SongList from '../content/SongList.vue';
import { useTransientNotice } from '../content/notice';
import { recallFocus, rememberFocus, sourceFocusFromEvent } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import type { LibrarySectionKey, TvContentCard } from '../native/contentTypes';
import { useTvAppStore, type TvTrack } from '../stores/app';
import { useTvLibraryStore, type LibrarySectionState } from '../stores/library';
import { useTvPlayerStore } from '../stores/player';

const PAGE_KEY = 'library-favorites';
const app = useTvAppStore();
const library = useTvLibraryStore();
const player = useTvPlayerStore();
const router = useRouter();
const { notice, show } = useTransientNotice();

const tabs = [
  { id: 'songs', label: '单曲', section: 'favoriteSongs' },
  { id: 'artists', label: '歌手', section: 'favoriteArtists' },
  { id: 'albums', label: '专辑', section: 'favoriteAlbums' },
  { id: 'mvs', label: 'MV', section: 'favoriteMvs' },
] as const;

const activeTab = ref<(typeof tabs)[number]['id']>('songs');
const loadingMore = ref(false);
const menuTrack = ref<TvTrack | null>(null);
const menuSourceFocusId = ref<string | undefined>(undefined);

const loggedIn = computed(() => app.session.status === 'loggedIn');
const errorText = computed(() => section.value.errorCode === 'AUTH_EXPIRED'
  ? '登录已失效，请重新扫码'
  : section.value.errorCode === 'LIBRARY_LIKED_LIST_MISSING'
    ? '未识别到喜欢歌单，请返回资料库，从创建或收藏的歌单中打开'
    : section.value.errorCode === 'CONTENT_INVALID'
      ? '酷狗返回的收藏数据暂时无法解析，你的收藏没有被清空'
      : '收藏同步失败，请检查网络后重试');

const activeSectionKey = computed<LibrarySectionKey>(() => tabs.find((tab) => tab.id === activeTab.value)?.section ?? 'favoriteSongs');
const section = computed<LibrarySectionState>(() => library.sections[activeSectionKey.value] ?? { items: [], nextCursor: null, total: null, status: 'loading' });

const detailRoutes: Record<string, string> = {
  playlist: 'playlist-detail',
  album: 'album-detail',
  artist: 'artist-detail',
  song: 'song-detail',
  mv: 'mv-detail',
};

const songTracks = computed<TvTrack[]>(() =>
  activeSectionKey.value === 'favoriteSongs'
    ? section.value.items
        .filter((card) => card.kind === 'song')
        .map((card) => ({ id: card.id, title: card.title, artist: card.subtitle, album: '', artwork: card.artwork, durationMs: 0 }))
    : [],
);

const trackIds = computed(() => songTracks.value.map((track) => track.id));
const firstContentFocus = computed(() => activeTab.value === 'songs' && songTracks.value.length
  ? `favorites-songs-${songTracks.value[0].id}`
  : section.value.items.length ? `favorites-${activeTab.value}-0`
    : section.value.status === 'error' ? 'favorites-retry' : 'favorites-sync');

function playTrack(track: TvTrack): void {
  const index = Math.max(0, songTracks.value.findIndex((item) => item.id === track.id));
  void player.playQueue(trackIds.value, index).catch(() => undefined);
}

function openCard(card: TvContentCard, event?: Event): void {
  const name = detailRoutes[card.kind];
  if (!name) return;
  const focusId = event ? sourceFocusFromEvent(event) : undefined;
  void router.push({ name, params: { id: card.id }, ...(focusId ? { query: { sourceFocusId: focusId } } : {}) });
}

function openMenu(track: TvTrack, sourceFocusId?: string): void {
  menuTrack.value = track;
  menuSourceFocusId.value = sourceFocusId ?? `favorites-songs-${track.id}`;
}

function switchTab(tab: (typeof tabs)[number]): void {
  activeTab.value = tab.id;
  void nextTick(() => tvFocusRegistry.restore(`favorites-tab-${tab.id}`));
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

onMounted(() => {
  void nextTick(() => tvFocusRegistry.restore(loggedIn.value ? recallFocus(PAGE_KEY) ?? 'favorites-tab-songs' : 'favorites-login'));
});

// QR confirmation and restored sessions can arrive after this page is mounted.
watch([loggedIn, activeSectionKey, () => app.session.displayName], ([signedIn]) => {
  if (signedIn && !library.sections[activeSectionKey.value]) void library.loadSection(activeSectionKey.value);
}, { immediate: true });

onBeforeUnmount(() => rememberFocus(PAGE_KEY));
</script>

<template>
  <section class="favorites-page" data-page="library-favorites" :data-default-focus-id="loggedIn ? `favorites-tab-${activeTab}` : 'favorites-login'">
    <header class="page-header">
      <span class="eyebrow">LIBRARY</span>
      <h1>我喜欢的</h1>
      <p>收藏的单曲、歌手、专辑与 MV。</p>
      <p v-if="activeTab === 'songs' && songTracks.length" class="song-count">{{ section.total !== null ? `共 ${section.total} 首 · ` : '' }}已加载 {{ songTracks.length }} 首</p>
      <button v-if="loggedIn" v-tv-focus type="button" class="sync-action" data-focus-id="favorites-sync" data-focus-left="nav-library" data-focus-down="favorites-tab-songs" :aria-busy="section.status === 'loading'" @click="library.loadSection(activeSectionKey)"><TvIcon name="refresh" /> 同步收藏</button>
    </header>

    <div v-if="!loggedIn" class="login-prompt">
      <p>登录后即可查看你的收藏</p>
      <button v-tv-focus type="button" data-focus-id="favorites-login" @click="requestLogin">立即登录</button>
    </div>

    <template v-else>
      <div class="tab-bar" role="tablist" aria-label="收藏类别">
        <button
          v-for="(tab, index) in tabs"
          :key="tab.id"
          v-tv-focus
          type="button"
          role="tab"
          :aria-selected="activeTab === tab.id"
          class="tab"
          :class="{ active: activeTab === tab.id }"
          :data-favorites-tab="tab.id"
          :data-focus-id="`favorites-tab-${tab.id}`"
          :data-focus-left="index === 0 ? 'nav-library' : `favorites-tab-${tabs[index - 1].id}`"
          :data-focus-right="`favorites-tab-${tabs[Math.min(index + 1, tabs.length - 1)].id}`"
          data-focus-up="favorites-sync"
          :data-focus-down="firstContentFocus"
          @click="switchTab(tab)"
        >{{ tab.label }}</button>
      </div>

      <template v-if="activeSectionKey === 'favoriteSongs'">
        <SongList
          v-if="songTracks.length"
          :tracks="songTracks"
          group="favorites-songs"
          :down-focus-id="section.status === 'error' ? 'pagination-retry' : section.nextCursor || section.status === 'loading' ? 'pagination-load-more' : undefined"
          up-focus-id="favorites-tab-songs"
          left-focus-id="nav-library"
          @play="playTrack"
          @menu="(track) => openMenu(track, `favorites-songs-${track.id}`)"
        />
        <p v-else-if="section.status === 'ready'" class="empty">当前账号返回的喜欢列表为空，可按“同步收藏”重试。</p>
      </template>
      <template v-else>
        <ContentGrid
          v-if="section.items.length"
          :cards="section.items"
          :group="`favorites-${activeTab}`"
          :down-focus-id="section.status === 'error' ? 'pagination-retry' : section.nextCursor || section.status === 'loading' ? 'pagination-load-more' : undefined"
          @select="(card, event) => openCard(card, event)"
        />
        <p v-else-if="section.status === 'ready'" class="empty">暂无内容</p>
      </template>

      <p v-if="section.status === 'error'" class="empty" role="alert">{{ errorText }}<small class="error-code">诊断代码：{{ section.errorCode ?? 'UNKNOWN' }}</small></p>
      <button v-if="section.status === 'error' && (!section.items.length || section.errorCode === 'AUTH_EXPIRED')" v-tv-focus type="button" class="sync-action" data-focus-id="favorites-retry" data-focus-left="nav-library" @click="section.errorCode === 'AUTH_EXPIRED' ? requestLogin() : library.loadSection(activeSectionKey)">{{ section.errorCode === 'AUTH_EXPIRED' ? '重新扫码登录' : '重试同步' }}</button>

      <ContentSkeleton v-if="section.status === 'loading' && !section.items.length" :variant="activeTab === 'songs' ? 'songs' : 'cards'" :count="4" label="正在同步收藏" />

      <PaginationStatus
        v-if="section.items.length"
        :item-focus-ids="activeTab === 'songs' ? songTracks.map(track => `favorites-songs-${track.id}`) : section.items.map((_, index) => `favorites-${activeTab}-${index}`)"
        :focus-context="activeTab" left-focus-id="nav-library"
        :loading="section.status === 'loading'"
        :has-more="Boolean(section.nextCursor)"
        :error="section.status === 'error'"
        @load-more="loadMore"
        @retry="loadMore"
      />
    </template>

    <p v-if="notice" class="notice" role="status">{{ notice }}</p>

    <SongActions
      :favorite="true"
      :track="menuTrack"
      :source-focus-id="menuSourceFocusId"
      @close="menuTrack = null"
    />

  </section>
</template>

<style scoped>
.favorites-page { padding:12px 22px var(--tv-content-bottom-safe); min-width:0; }
.page-header { position:relative; padding:10px 4px 26px; }.page-header h1 { margin:8px 0; font-size:54px; letter-spacing:-1.5px; }
.page-header p { margin:12px 0 0; color:var(--tv-text-secondary); font-size:22px; }
.eyebrow { color:var(--tv-accent); font-size:16px; letter-spacing:3px; }
.page-header>.sync-action { position:absolute; right:8px; top:44px; }
.sync-action,.login-prompt button { min-height:60px; padding:0 28px; border:1px solid var(--tv-border); border-radius:16px; color:var(--tv-text); font-size:22px; background:var(--tv-glass); }
.tab-bar { display:flex; gap:16px; margin:8px 4px 24px; }
.tab-bar .tab { min-width:120px; min-height:60px; border:0; border-radius:16px; font-size:25px; color:var(--tv-text-secondary); background:transparent; }
.tab-bar .tab.active { color:var(--tv-text); background:#ffffff1f; }
.empty,.login-prompt { padding:36px 12px; color:var(--tv-text-secondary); font-size:23px; line-height:1.6; }
.error-code { display:block; font-size:19px; margin-top:14px; color:var(--tv-accent); }
.skeleton { min-height:220px; border-radius:var(--tv-radius-panel); background:#ffffff0d; }
.notice { position:fixed; left:50%; bottom:40px; z-index:20; padding:16px 28px; border-radius:18px; transform:translateX(-50%); color:var(--tv-text); font-size:22px; background:#29252ff5; }
</style>
