<script setup lang="ts">
import ContentSkeleton from '../content/ContentSkeleton.vue';
import TvIcon from '../components/TvIcon.vue';
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import PaginationStatus from '../content/PaginationStatus.vue';
import SongList from '../content/SongList.vue';
import SongActions from '../content/SongActions.vue';
import { recallFocus, rememberFocus } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import { useTvAppStore, type TvTrack } from '../stores/app';
import { useTvLibraryStore, type LibrarySectionState } from '../stores/library';
import { useTvPlayerStore } from '../stores/player';

const menuTrack = ref<TvTrack | null>(null);
const PAGE_KEY = 'library-history';
const app = useTvAppStore();
const library = useTvLibraryStore();
const player = useTvPlayerStore();
const router = useRouter();

const loadingMore = ref(false);

const loggedIn = computed(() => app.session.status === 'loggedIn');
const section = computed<LibrarySectionState>(() => library.sections.history ?? { items: [], nextCursor: null, total: null, status: 'loading' });

const tracks = computed<TvTrack[]>(() =>
  section.value.items
    .filter((card) => card.kind === 'song')
    .map((card) => ({ id: card.id, title: card.title, artist: card.subtitle, album: '', artwork: card.artwork, durationMs: 0 })),
);

const totalCount = computed(() => section.value.total ?? tracks.value.length);
const defaultFocusId = computed(() => (tracks.value.length ? `history-${tracks.value[0].id}` : 'history-empty-home'));

function playTrack(track: TvTrack): void {
  const index = Math.max(0, tracks.value.findIndex((item) => item.id === track.id));
  void player.playQueue(tracks.value.map((item) => item.id), index).catch(() => undefined);
}

async function loadMore(): Promise<void> {
  const cursor = section.value.nextCursor;
  if (!cursor || loadingMore.value) return;
  loadingMore.value = true;
  await library.loadSection('history', cursor);
  loadingMore.value = false;
}

function requestLogin(): void {
  window.dispatchEvent(new CustomEvent('echo-tv-login-requested'));
}

function goHome(): void {
  void router.push({ name: 'home' });
}

onMounted(async () => {
  if (loggedIn.value && library.sections.history === undefined) await library.loadSection('history');
  void nextTick(() => tvFocusRegistry.restore(recallFocus(PAGE_KEY) ?? defaultFocusId.value));
});

onBeforeUnmount(() => rememberFocus(PAGE_KEY));
</script>

<template>
  <section class="history-page" data-page="library-history">
    <header class="page-header">
      <span class="eyebrow">LIBRARY</span>
      <h1>播放历史</h1>
      <p>回听曾经响起的声音。</p>
    </header>

    <div v-if="!loggedIn" class="login-prompt">
      <p>登录后即可查看播放历史</p>
      <button v-tv-focus type="button" data-focus-id="history-login" @click="requestLogin">立即登录</button>
    </div>

    <template v-else>
      <div class="stats-panel">
        <span class="stats-glyph" aria-hidden="true"><TvIcon name="history" :size="28" /></span>
        <div>
          <strong>{{ totalCount }}</strong>
          <small>条播放记录</small>
        </div>
      </div>

      <SongList
        v-if="tracks.length"
        :tracks="tracks"
        group="history"
        :down-focus-id="section.status === 'error' ? 'pagination-retry' : section.nextCursor || section.status === 'loading' ? 'pagination-load-more' : undefined"
        @play="playTrack"
          @menu="menuTrack = $event"
      />
      <div v-else-if="section.status === 'ready'" class="empty">
        <p>还没有播放历史</p>
        <button v-tv-focus type="button" data-focus-id="history-empty-home" @click="goHome">回到首页</button>
      </div>

      <p v-if="section.status === 'error'" class="empty">加载失败，请重试</p>
      <button v-if="section.status === 'error' && !tracks.length" v-tv-focus type="button" data-focus-id="history-retry" @click="library.loadSection('history')">重新加载</button>

      <ContentSkeleton v-if="section.status === 'loading' && !section.items.length" variant="songs" :count="4" label="正在加载最近播放" />

      <PaginationStatus
        v-if="tracks.length"
        :item-focus-ids="tracks.map(track => `history-${track.id}`)"
        left-focus-id="nav-library"
        :loading="section.status === 'loading'"
        :has-more="Boolean(section.nextCursor)"
        :error="section.status === 'error'"
        @load-more="loadMore"
        @retry="loadMore"
      />
    </template>
    <SongActions :track="menuTrack" :source-focus-id="menuTrack ? 'history-' + menuTrack.id : undefined" @close="menuTrack = null" />
  </section>
</template>

<style scoped>
.history-page { padding: 4px 14px 150px; min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size: 44px; letter-spacing: -1.6px; }
.page-header p { margin: 10px 0 0; color: var(--tv-text-secondary); font-size: 17px; }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.stats-panel { display: flex; align-items: center; gap: 18px; margin: 22px 0 18px; padding: 20px 24px; border: 1px solid var(--tv-border); border-radius: var(--tv-radius-card); background: rgba(255, 255, 255, 0.04); }
.stats-glyph { width: 52px; height: 52px; display: grid; place-items: center; border-radius: 15px; font-size: 25px; background: linear-gradient(145deg, #2499ff, #7845c0); }
.stats-panel strong { display: block; font-size: 30px; }
.stats-panel small { color: var(--tv-text-secondary); font-size: 14px; }
.empty { padding: 30px 8px; color: var(--tv-text-secondary); font-size: 16px; }
.empty button { min-width: 220px; height: 52px; border: 1px solid rgba(124, 188, 255, 0.35); border-radius: 26px; color: #dceeff; background: rgba(36, 102, 164, 0.3); }
.login-prompt { padding: 30px 8px; }
.login-prompt p { color: var(--tv-text-secondary); font-size: 16px; }
.login-prompt button { min-width: 220px; height: 52px; border: 1px solid rgba(124, 188, 255, 0.35); border-radius: 26px; color: #dceeff; background: rgba(36, 102, 164, 0.3); }
.skeleton { min-height: 200px; border-radius: var(--tv-radius-panel); background: rgba(255, 255, 255, 0.05); }
</style>
