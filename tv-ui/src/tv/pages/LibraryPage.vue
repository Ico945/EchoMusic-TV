<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import ContentSkeleton from '../content/ContentSkeleton.vue';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { recallFocus, rememberFocus, sourceFocusFromEvent, mayAutoRestorePageFocus } from '../content/routeMemory';
import { usePaginationFocus } from '../content/paginationFocus';
import { tvFocusRegistry } from '../focus/directive';
import type { TvContentCard } from '../native/contentTypes';
import { useTvAppStore } from '../stores/app';
import { useTvLibraryStore } from '../stores/library';

const app = useTvAppStore();
const library = useTvLibraryStore();
const route = useRoute();
const router = useRouter();
const tabs = [
  { key: 'allPlaylists', label: '全部歌单' },
  { key: 'favoritePlaylists', label: '收藏的歌单' },
  { key: 'createdPlaylists', label: '创建的歌单' },
] as const;
const shortcuts = [
  { id: 'favorites', label: '喜欢的音乐', icon: 'heart' },
  { id: 'history', label: '最近播放', icon: 'history' },
  { id: 'cloud', label: '音乐云盘', icon: 'cloud' },
  { id: 'purchased', label: '已购音乐', icon: 'purchased' },
];
const loggedIn = computed(() => app.session.status === 'loggedIn');
const section = computed(() => route.query.section === 'createdPlaylists' ? 'createdPlaylists' : route.query.section === 'favoritePlaylists' ? 'favoritePlaylists' : 'allPlaylists');
const state = computed(() => library.sections[section.value]);
const cards = computed(() => state.value?.items ?? []);
const tabId = computed(() => 'library-tab-' + section.value);
const firstContent = computed(() => cards.value.length ? cardFocus(0) : state.value?.errorCode === 'AUTH_EXPIRED' ? 'library-relogin' : 'library-action');
const showSyncInfo = ref(false);
const syncInfo = computed(() => {
  const pages = Object.values(state.value?.syncPages ?? {});
  const sum = (key: 'received' | 'created' | 'collected' | 'albums' | 'missingCreator') => pages.reduce((total, page) => total + page[key], 0);
  return pages.length ? `已读取 ${pages.length} 页 · 接口返回 ${sum('received')} 项 · 创建 ${sum('created')} / 收藏 ${sum('collected')} / 专辑 ${sum('albums')} · 缺创建者 ${sum('missingCreator')}` : '尚未取得可用的同步结果';
});
let alive = true;
const pageElement = ref<HTMLElement | null>(null);
const { begin: beginPaginationFocus } = usePaginationFocus({
  scope: () => pageElement.value,
  itemFocusIds: () => cards.value.map((_, index) => cardFocus(index)),
  loading: () => state.value?.status === 'loading',
  error: () => state.value?.status === 'error',
  hasMore: () => Boolean(state.value?.nextCursor),
  context: () => section.value,
  fallbackFocusId: () => tabId.value,
});

function cardFocus(index: number): string {
  return cards.value[index] ? 'library-card-' + encodeURIComponent(cards.value[index].id) : 'library-action';
}
function selectTab(key: string): void {
  void router.replace({ name: 'library', query: { section: key } });
}
function openPlaylist(card: TvContentCard, event: Event): void {
  void router.push({ name: 'playlist-detail', params: { id: card.detailId ?? (card.id.startsWith('playlist:') ? card.id : 'playlist:' + card.id) }, query: { sourceFocusId: sourceFocusFromEvent(event), title: card.title, artwork: card.artwork } });
}
function requestLogin(): void {
  window.dispatchEvent(new CustomEvent('echo-tv-login-requested'));
}
async function load(more = false): Promise<void> {
  if (state.value?.status === 'loading') return;
  const requestedSection = section.value;
  const source = document.activeElement as HTMLElement | null;
  if (more && source?.dataset.focusId === 'library-action') beginPaginationFocus(source);
  const previousFocus = source?.dataset.focusId;
  await library.loadSection(requestedSection, more ? state.value?.nextCursor ?? undefined : undefined);
  await nextTick();
  if (!alive || requestedSection !== section.value) return;
  // Never reclaim focus from a sidebar, dialog, or a later user action.
  const active = document.activeElement as HTMLElement | null;
  if (!more && previousFocus === 'library-action' && active?.dataset.focusId === previousFocus && cards.value.length && state.value?.status === 'ready') {
    tvFocusRegistry.restore(cardFocus(0));
  }
}
watch([loggedIn, section, () => app.session.displayName], ([signedIn]) => {
  // Returning from a detail page must retain later pages and their focused card.
  // Account changes still clear the store synchronously; explicit sync refreshes it.
  if (signedIn && !state.value) void library.loadSection(section.value);
}, { immediate: true });
onMounted(() => {
  void nextTick(() => {
    if (!alive || !mayAutoRestorePageFocus('nav-library')) return;
    const remembered = recallFocus('library');
    const exists = remembered && Array.from(document.querySelectorAll<HTMLElement>('[data-page=library] [data-focus-id]')).some(el => el.dataset.focusId === remembered);
    tvFocusRegistry.restore(exists ? remembered : loggedIn.value ? tabId.value : 'library-login');
  });
});
onBeforeUnmount(() => { alive = false; rememberFocus('library'); });
</script>

<template>
  <section ref="pageElement" class="library-page core-browse-page" data-page="library">
    <header class="library-header">
      <div><span class="eyebrow">YOUR MUSIC</span><h1>资料库</h1><p>喜欢的音乐，随时回到这里。</p></div>
      <div v-if="loggedIn" class="header-actions"><button v-tv-focus type="button" class="refresh-button likes-entry" data-focus-id="library-liked-entry" data-focus-left="nav-library" data-focus-right="library-refresh" :data-focus-down="tabId" @click="router.push({ name: 'favorites' })"><TvIcon name="heart" /> 我喜欢的音乐</button><button v-tv-focus class="refresh-button" data-focus-id="library-refresh" data-focus-left="library-liked-entry" data-focus-right="library-sync-info" :data-focus-down="tabId" type="button" :aria-busy="state?.status === 'loading'" @click="load()"><TvIcon name="refresh" /> 同步歌单</button><button v-tv-focus class="refresh-button" type="button" data-focus-id="library-sync-info" data-focus-left="library-refresh" data-focus-right="library-sync-info" :data-focus-down="tabId" :aria-expanded="showSyncInfo" @click="showSyncInfo = !showSyncInfo"><TvIcon name="info" /> 同步详情</button></div>
    </header>
    <section v-if="!loggedIn" class="library-empty">
      <span class="empty-symbol" aria-hidden="true"><TvIcon name="music" :size="64" /></span><h2>把你的音乐带到客厅</h2>
      <p>使用酷狗概念版扫码，查看你创建和收藏的歌单。</p>
      <button v-tv-focus type="button" class="library-button" data-focus-id="library-login" data-focus-left="nav-library" @click="requestLogin"><TvIcon name="qrCode" /> 扫码登录</button>
    </section>
    <template v-else>
      <nav class="library-tabs" aria-label="歌单分类">
        <button v-for="(tab, index) in tabs" :key="tab.key" v-tv-focus type="button" :data-library-section="tab.key" :data-focus-id="'library-tab-' + tab.key" :data-focus-left="index === 0 ? 'nav-library' : 'library-tab-' + tabs[index - 1].key" :data-focus-right="'library-tab-' + tabs[Math.min(index + 1, tabs.length - 1)].key" data-focus-up="library-liked-entry" :data-focus-down="firstContent" :aria-pressed="section === tab.key" @click="selectTab(tab.key)">{{ tab.label }}</button>
        <span v-if="cards.length" class="loaded-count">已加载 {{ cards.length }} 个{{ state?.nextCursor ? ' · 还有更多' : '' }}</span>
      </nav>
      <p v-if="showSyncInfo" class="sync-info" data-library-diagnostics>{{ syncInfo }}<span v-if="state?.errorCode"> · {{ state.errorCode }}</span></p>
      <ContentSkeleton v-if="!cards.length && (!state || state.status === 'loading')" :count="4" label="正在同步你的歌单" />
      <div v-if="cards.length" class="playlist-grid" :aria-busy="state?.status === 'loading'">
        <button v-for="(card, index) in cards" :key="card.id" v-tv-focus type="button" class="playlist-card tv-card" :data-focus-id="cardFocus(index)" data-focus-group="library-playlists" :data-focus-left="index % 4 === 0 ? 'nav-library' : cardFocus(index - 1)" :data-focus-right="index % 4 === 3 || index === cards.length - 1 ? cardFocus(index) : cardFocus(index + 1)" :data-focus-up="index < 4 ? tabId : cardFocus(index - 4)" :data-focus-down="index + 4 < cards.length ? cardFocus(index + 4) : 'library-action'" @click="openPlaylist(card, $event)">
          <span class="playlist-art" :class="'cover-tone-' + index % 4"><img v-if="card.artwork" :src="card.artwork" alt="" loading="lazy"><span v-else aria-hidden="true"><TvIcon name="music" :size="64" /></span><i aria-hidden="true">打开歌单 <TvIcon name="chevronRight" :size="20" /></i></span>
          <strong>{{ card.title }}</strong><small>{{ card.subtitle || (section === 'favoritePlaylists' ? '收藏的歌单' : '我的歌单') }}</small>
        </button>
      </div>
      <section v-if="!cards.length && state && state.status !== 'loading'" class="library-empty" role="status">
        <template v-if="state.status === 'error'"><h2>{{ state.errorCode === 'AUTH_EXPIRED' ? '请重新登录酷狗' : '歌单同步失败' }}</h2><p>请检查网络；如果登录已过期，请重新扫码。你的收藏没有被清空。</p></template>
        <template v-else-if="state.nextCursor"><h2>继续查找你的歌单</h2><p>已读取的页面还没有这一分类，后面还有内容。</p></template>
        <template v-else><span class="empty-symbol" aria-hidden="true"><TvIcon name="heart" :size="60" /></span><h2>{{ section === 'allPlaylists' ? '暂未同步到歌单' : '当前分类未显示歌单' }}</h2><p>{{ section === 'allPlaylists' ? '可以重新同步；若手机上有歌单而这里仍为空，请打开「同步详情」排查。你的收藏不会因此被清空。' : '请切换到「全部歌单」查看；分类信息不完整时，歌单仍会保留在那里。' }}</p></template>
      </section>
      <p v-if="cards.length && state?.status === 'error'" class="inline-error" role="alert">后续内容加载失败，已加载的歌单仍可浏览。</p>
      <button v-if="state?.errorCode === 'AUTH_EXPIRED'" v-tv-focus type="button" class="library-button" data-focus-id="library-relogin" :data-focus-up="tabId" data-focus-down="library-action" data-focus-left="nav-library" @click="requestLogin"><TvIcon name="qrCode" /> 重新扫码登录</button>
      <button v-tv-focus class="library-button page-action" type="button" data-focus-id="library-action" :data-focus-up="state?.errorCode === 'AUTH_EXPIRED' ? 'library-relogin' : cards.length ? cardFocus(cards.length - 1) : tabId" data-focus-down="library-favorites" data-focus-left="nav-library" :aria-busy="state?.status === 'loading'" @click="load(Boolean(state?.nextCursor))">{{ state?.status === 'loading' ? '正在同步…' : state?.status === 'error' ? '重试同步' : state?.nextCursor ? '加载更多歌单' : '重新同步' }}</button>
      <nav class="library-shortcuts" aria-label="更多音乐">
        <button v-for="(item, index) in shortcuts" :key="item.id" v-tv-focus type="button" :data-focus-id="'library-' + item.id" data-focus-up="library-action" :data-focus-left="index === 0 ? 'nav-library' : 'library-' + shortcuts[index - 1].id" @click="router.push({ name: item.id })"><span aria-hidden="true"><TvIcon :name="item.icon" :size="28" /></span>{{ item.label }}<i aria-hidden="true"><TvIcon name="chevronRight" :size="20" /></i></button>
      </nav>
      <footer class="remote-hint">方向键移动 <b>·</b> 确认打开 <b>·</b> 返回上一级</footer>
    </template>
  </section>
</template>

<style scoped>
.library-page { padding:12px 22px var(--tv-content-bottom-safe); min-width:0; }
.library-header { display:flex; align-items:center; justify-content:space-between; padding:10px 4px 26px; }
.header-actions { display:flex; gap:18px; }.likes-entry { color:var(--tv-accent); }.library-empty small { color:var(--tv-accent); font-size:19px; }
.sync-info { margin:4px 12px 20px; font-size:20px; line-height:1.6; color:var(--tv-text-secondary); overflow-wrap:anywhere; }
.eyebrow { font-size:16px; letter-spacing:3px; font-weight:650; color:var(--tv-accent); }
h1 { margin:8px 0; font-size:54px; line-height:1.1; letter-spacing:-1.5px; }
.library-header p { margin:10px 0 0; font-size:22px; color:var(--tv-text-secondary); }
.refresh-button,.library-button { min-height:58px; padding:0 28px; border:1px solid var(--tv-border); border-radius:16px; background:var(--tv-glass); color:var(--tv-text); font-size:21px; font-weight:600; }
.library-tabs { display:flex; align-items:center; gap:18px; margin:8px 4px 26px; }
.library-tabs button { min-height:60px; padding:0 26px; border:0; border-radius:16px; background:transparent; color:var(--tv-text-secondary); font-size:25px; font-weight:650; }
.library-tabs button[aria-pressed="true"] { background:rgba(255,255,255,.12); color:white; }
.loaded-count { margin-left:auto; color:var(--tv-text-secondary); font-size:19px; }
.playlist-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:30px 24px; padding:4px; }
.playlist-card { min-width:0; padding:8px; border:0; border-radius:22px; background:transparent; color:var(--tv-text); text-align:left; }
.playlist-art { position:relative; display:grid; place-items:center; aspect-ratio:1; border-radius:16px; overflow:hidden; box-shadow:0 16px 28px #0005; }
.playlist-art img { width:100%; height:100%; object-fit:cover; }
.playlist-art>span { font-size:88px; color:#ffffffb3; }
.playlist-art i { position:absolute; bottom:16px; left:16px; right:16px; padding:12px; border-radius:10px; background:#141416dd; text-align:center; font-size:18px; font-style:normal; opacity:0; }
.playlist-card:focus .playlist-art i { opacity:1; }
.playlist-card strong,.playlist-card small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.playlist-card strong { margin:16px 2px 6px; font-size:24px; }
.playlist-card small { margin:0 2px 6px; font-size:20px; color:var(--tv-text-secondary); }
.cover-tone-0 { background:linear-gradient(140deg,#5c2939,#272027); }.cover-tone-1 { background:linear-gradient(140deg,#355455,#20262c); }.cover-tone-2 { background:linear-gradient(140deg,#696144,#2b2923); }.cover-tone-3 { background:linear-gradient(140deg,#4e465f,#22202a); }
.library-empty { min-height:290px; padding:24px; display:flex; flex-direction:column; align-items:center; justify-content:center; text-align:center; }
.empty-symbol { font-size:60px; color:var(--tv-accent); }
.library-empty h2 { margin:18px 0 8px; font-size:32px; }
.library-empty p { font-size:21px; line-height:1.6; color:var(--tv-text-secondary); max-width:820px; }
.page-action { display:block; margin:22px auto 30px; min-width:240px; }
.inline-error { color:#ffc1c5; font-size:21px; }
.library-shortcuts { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:18px; padding:12px 8px; border-top:1px solid var(--tv-border); }
.library-shortcuts button { display:flex; align-items:center; gap:14px; min-width:0; min-height:74px; padding:0 18px; border:0; border-radius:16px; font-size:21px; background:var(--tv-glass); color:var(--tv-text); }
.library-shortcuts span { font-size:28px; color:var(--tv-accent); }.library-shortcuts i { margin-left:auto; font-style:normal; color:var(--tv-text-secondary); }
.remote-hint { padding:20px 8px; color:var(--tv-text-secondary); font-size:18px; }.remote-hint b { margin:0 14px; font-weight:400; }
.library-button { display:inline-flex; align-items:center; justify-content:center; gap:10px; padding:0 20px; }
</style>
