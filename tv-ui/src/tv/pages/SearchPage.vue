<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import TvIcon from '../components/TvIcon.vue';
import ContentGrid from '../content/ContentGrid.vue';
import ContentSkeleton from '../content/ContentSkeleton.vue';
import PaginationStatus from '../content/PaginationStatus.vue';
import SongActions from '../content/SongActions.vue';
import SongList from '../content/SongList.vue';
import { mayAutoRestorePageFocus, recallFocus, rememberFocus } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import { useBackLayerRegistry } from '../input/backCoordinator';
import { nativeClient } from '../native/client';
import { SEARCH_CATEGORIES, type SearchCategory, type TvContentCard } from '../native/contentTypes';
import type { TvTrack } from '../stores/app';
import { useTvPlayerStore } from '../stores/player';
import { useTvSearchStore } from '../stores/search';
import { useTvAppStore } from '../stores/app';

const search = useTvSearchStore();
const app = useTvAppStore();
const player = useTvPlayerStore();
const router = useRouter();
const backLayers = useBackLayerRegistry();
const inputElement = ref<HTMLInputElement | null>(null);
const inputFocused = ref(false);
const historyClearArmed = ref(false);
const hotRetrying = ref(false);
async function retryHot(): Promise<void> {
  if (hotRetrying.value) return;
  hotRetrying.value = true;
  await search.loadHotAndHistory();
  const owned = alive && activeId() === 'search-hot-retry';
  hotRetrying.value = false;
  await nextTick();
  if (owned && alive) tvFocusRegistry.restore(search.hotError ? 'search-hot-retry' : search.hotTerms.length ? 'search-hot-0' : 'search-input');
}
const menuTrack = ref<TvTrack | null>(null);
const menuSourceFocusId = ref<string | undefined>(undefined);
let releaseImeLayer: (() => void) | undefined;
let releasePageLayer: (() => void) | undefined;
let alive = true;
let intent = 0;
const labels = { song: '单曲', playlist: '歌单', album: '专辑', artist: '歌手', lyric: '歌词', mv: 'MV' };
const detailRoutes: Record<string, string> = {
  playlist: 'playlist-detail', album: 'album-detail', artist: 'artist-detail', song: 'song-detail', mv: 'mv-detail',
};
const songCards = computed<TvTrack[]>(() => search.isSongCategory
  ? search.results.items.filter(card => card.kind === 'song')
    .map(card => ({ id: card.id, title: card.title, artist: card.subtitle, album: '', artwork: card.artwork, durationMs: 0 }))
  : []);
const tabFocus = computed(() => `search-tab-${search.category}`);
const contentFocus = computed(() => {
  if (!search.submitted) return search.history.length ? 'search-history-0'
    : search.hotTerms.length ? 'search-hot-0' : search.hotError ? 'search-hot-retry' : tabFocus.value;
  return search.firstResultFocusId ?? (search.loading ? tabFocus.value : search.error ? search.requiresLogin ? 'search-login' : 'search-retry' : 'search-edit');
});

function activeId(): string | undefined {
  return (document.activeElement as HTMLElement | null)?.dataset.focusId;
}

async function runAndEnter(action: () => Promise<void>): Promise<void> {
  const operation = ++intent;
  const origin = activeId();
  await action();
  await nextTick();
  // A slow response must never reclaim focus from the rail, tabs or another route.
  if (!alive || operation !== intent || (activeId() && activeId() !== origin)) return;
  tvFocusRegistry.restore(contentFocus.value);
}

function submitQuery(): void {
  if (!search.query.trim()) { tvFocusRegistry.restore('search-input'); openIme(); return; }
  if (search.loading && search.query.trim() === search.submittedQuery) return;
  if (inputFocused.value) closeIme();
  tvFocusRegistry.restore('search-submit');
  void runAndEnter(() => search.submit());
}

async function loadMore(): Promise<void> {
  ++intent;
  await (search.error ? search.retry() : search.searchNext());
}

function chooseCategory(category: SearchCategory): void {
  intent++;
  void search.setCategory(category);
  // Keep the selected tab focused. Down enters its results when ready.
}

function chooseTerm(term: string): void {
  search.query = term;
  submitQuery();
}

function showSuggestions(clear = false): void {
  intent++;
  if (clear) search.query = '';
  search.showSuggestions();
  void nextTick(() => { if (alive) tvFocusRegistry.restore('search-input'); });
}

function requestLogin(): void {
  window.dispatchEvent(new CustomEvent('echo-tv-login-requested'));
}

function openIme(): void {
  if (inputFocused.value) return;
  inputFocused.value = true;
  releaseImeLayer?.();
  releaseImeLayer = backLayers.register({ kind: 'ime', close: () => closeIme() });
  // Make the DOM editable before asking Android for an input connection.
  // Merely focusing this field for D-pad navigation must not summon the IME.
  void nextTick(() => {
    if (!alive || !inputFocused.value) return;
    // WebView must recreate its native input connection after readonly changes.
    inputElement.value?.blur();
    inputElement.value?.focus({ preventScroll: true });
    void nativeClient.request('app.showIme', {}).catch(() => undefined);
  });
}

function closeIme(requestNative = true): void {
  inputFocused.value = false;
  releaseImeLayer?.();
  releaseImeLayer = undefined;
  inputElement.value?.blur();
  if (alive) tvFocusRegistry.restore('search-submit');
  if (requestNative) void nativeClient.request('app.hideIme', {}).catch(() => undefined);
}

function onInputEnter(): void {
  if (!inputFocused.value) openIme();
  else submitQuery();
}

function onNativeImeHidden(): void {
  if (!alive) return;
  if (inputFocused.value) closeIme(false);
  // Some WebViews return focus to the input while dismissing the IME. Repair
  // only that input, never an already selected result or navigation button.
  else if (document.activeElement === inputElement.value) tvFocusRegistry.restore('search-submit');
}

function termLink(kind: 'history' | 'hot', index: number, direction: 'up' | 'down'): string {
  const terms = kind === 'history' ? search.history : search.hotTerms;
  if (direction === 'up') {
    if (index >= 4) return `search-${kind}-${index - 4}`;
    if (kind === 'history') return 'search-history-clear';
    if (kind === 'hot' && search.history.length) {
      const lastRow = Math.floor((search.history.length - 1) / 4) * 4;
      return `search-history-${Math.min(lastRow + index, search.history.length - 1)}`;
    }
    return tabFocus.value;
  }
  if (index + 4 < terms.length) return `search-${kind}-${index + 4}`;
  if (kind === 'history' && search.hotTerms.length) return `search-hot-${Math.min(index % 4, search.hotTerms.length - 1)}`;
  if (search.hotError) return 'search-hot-retry';
  return `search-${kind}-${index}`;
}

function playTrack(_track: TvTrack, index: number): void {
  void player.playQueue(songCards.value.map(item => item.id), index).catch(() => undefined);
}

function openCard(card: TvContentCard): void {
  const name = detailRoutes[card.kind];
  if (name) void router.push({ name, params: { id: card.id }, query: { sourceFocusId: activeId() } });
}

watch(() => search.submitted, (submitted) => {
  releasePageLayer?.();
  releasePageLayer = submitted ? backLayers.register({ kind: 'page', close: () => showSuggestions() }) : undefined;
}, { immediate: true });
watch(() => app.session.status, (status, previous) => {
  if (status === 'loggedIn' && previous !== status && search.submitted && search.requiresLogin) void search.retry();
});

function clearHistory(): void {
  if (!historyClearArmed.value) { historyClearArmed.value = true; return; }
  historyClearArmed.value = false;
  search.clearHistory();
  void nextTick(() => tvFocusRegistry.restore(search.hotTerms.length ? 'search-hot-0' : 'search-input'));
}

onMounted(() => {
  window.addEventListener('echo-tv-ime-hidden', onNativeImeHidden);
  const remembered = recallFocus('search');
  const exists = remembered && Array.from(inputElement.value?.closest('[data-page=search]')?.querySelectorAll<HTMLElement>('[data-focus-id]') ?? []).some(el => el.dataset.focusId === remembered);
  if (mayAutoRestorePageFocus('nav-search')) tvFocusRegistry.restore(exists ? remembered : 'search-input');
  if (!search.hotTerms.length) void search.loadHotAndHistory();
});
onBeforeUnmount(() => {
  alive = false;
  intent++;
  if ((document.activeElement as HTMLElement | null)?.closest('[data-page=search]')) rememberFocus('search');
  window.removeEventListener('echo-tv-ime-hidden', onNativeImeHidden);
  releasePageLayer?.();
  releaseImeLayer?.();
  if (inputFocused.value) void nativeClient.request('app.hideIme', {}).catch(() => undefined);
});
</script>

<template>
  <section class="search-page core-browse-page" data-page="search" @focusin="rememberFocus('search')" :data-default-focus-id="search.submitted ? 'search-submit' : 'search-input'" :data-search-submitted="String(search.submitted)" :data-ime-focused="String(inputFocused)">
    <header class="page-header"><span class="eyebrow">SEARCH</span><h1>搜索</h1><p>找到想听的音乐</p></header>
    <div class="search-bar">
      <div class="input-wrap">
        <TvIcon name="search" :size="30" />
        <input ref="inputElement" v-model="search.query" v-tv-focus :readonly="!inputFocused" type="text" inputmode="search" autocomplete="off" autocapitalize="off" spellcheck="false" enterkeyhint="search" maxlength="64"
          placeholder="搜索歌曲、歌单、专辑、歌手…" aria-label="搜索关键词" data-focus-id="search-input"
          data-focus-left="nav-search" :data-focus-right="search.query ? 'search-clear' : 'search-submit'" :data-focus-down="tabFocus" data-focus-up="search-input"
          :data-tv-ime-active="String(inputFocused)" @click="openIme" @keydown.enter.prevent.stop="onInputEnter" />
        <button v-if="search.query" v-tv-focus type="button" class="clear-query" aria-label="清空搜索" data-focus-id="search-clear"
          data-focus-left="search-input" data-focus-right="search-submit" :data-focus-down="tabFocus" @click="showSuggestions(true)"><TvIcon name="close" :size="26" /></button>
      </div>
      <button v-tv-focus class="submit" type="button" data-focus-id="search-submit" :data-focus-left="search.query ? 'search-clear' : 'search-input'"
        data-focus-right="search-submit" :data-focus-down="tabFocus" @click="submitQuery"><TvIcon name="search" />搜索</button>
    </div>
    <p class="remote-hint">确认键输入 · 方向键浏览<template v-if="search.submitted"> · 返回键重新搜索</template></p>
    <div class="category-tabs" role="tablist" aria-label="搜索类别">
      <button v-for="(category, index) in SEARCH_CATEGORIES" :key="category" v-tv-focus type="button" role="tab"
        :aria-selected="search.category === category" :aria-pressed="search.category === category" class="tab" :class="{ active: search.category === category }"
        :data-search-category="category" :data-focus-id="`search-tab-${category}`" data-focus-up="search-input"
        :data-focus-left="index ? `search-tab-${SEARCH_CATEGORIES[index - 1]}` : 'nav-search'"
        :data-focus-right="`search-tab-${SEARCH_CATEGORIES[Math.min(index + 1, SEARCH_CATEGORIES.length - 1)]}`" :data-focus-down="contentFocus"
        @click="chooseCategory(category)">{{ labels[category] }}</button>
    </div>
    <template v-if="!search.submitted">
      <section v-for="group in (['history', 'hot'] as const)" v-show="search[group === 'history' ? 'history' : 'hotTerms'].length" :key="group" class="term-section" :aria-label="group === 'history' ? '搜索历史' : '热门搜索'">
        <h2><TvIcon :name="group === 'history' ? 'history' : 'chart'" :size="26" />{{ group === 'history' ? '搜索历史' : '热门搜索' }}<button v-if="group === 'history'" v-tv-focus type="button" data-focus-id="search-history-clear" :data-focus-up="tabFocus" data-focus-down="search-history-0" @blur="historyClearArmed = false" @click="clearHistory">{{ historyClearArmed ? '再次确认清除' : '清除历史' }}</button></h2>
        <div class="term-grid">
          <button v-for="(term, index) in search[group === 'history' ? 'history' : 'hotTerms']" :key="term" v-tv-focus type="button" class="term-row"
            :data-focus-id="`search-${group}-${index}`"
            :data-focus-left="index % 4 === 0 ? 'nav-search' : `search-${group}-${index - 1}`"
            :data-focus-right="`search-${group}-${index % 4 === 3 ? index : Math.min(index + 1, search[group === 'history' ? 'history' : 'hotTerms'].length - 1)}`"
            :data-focus-up="termLink(group, index, 'up')" :data-focus-down="termLink(group, index, 'down')"
            @click="chooseTerm(term)"><span v-if="group === 'hot'" class="term-number">{{ String(index + 1).padStart(2, '0') }}</span><span>{{ term }}</span></button>
        </div>
      </section>
      <ContentSkeleton v-if="search.hotLoading && !search.hotTerms.length" variant="terms" :count="8" heading label="正在加载热门搜索" />
      <button v-if="search.hotError || hotRetrying" v-tv-focus type="button" class="hot-retry" data-focus-id="search-hot-retry" :data-focus-up="search.history.length ? `search-history-${search.history.length - 1}` : tabFocus" :aria-busy="hotRetrying" @click="retryHot">{{ hotRetrying ? '正在重试…' : '热搜暂时不可用，按确认重试；也可以直接输入关键词' }}</button>
    </template>
    <template v-else>
      <h2 class="results-heading">“{{ search.submittedQuery }}”<span>{{ labels[search.category] }}<template v-if="search.results.total !== null"> · {{ search.results.total }}</template></span></h2>
      <ContentSkeleton v-if="search.loading && !search.results.items.length" :variant="search.isSongCategory ? 'songs' : 'cards'" :count="4" :round="search.category === 'artist'" label="正在搜索" />
      <SongList v-else-if="search.isSongCategory && songCards.length" :tracks="songCards" :group="`search-${search.category}`"
        :up-focus-id="tabFocus" :down-focus-id="search.error ? 'pagination-retry' : search.results.nextCursor ? 'pagination-load-more' : undefined" left-focus-id="nav-search" @play="playTrack"
        @menu="(track) => { menuTrack = track; menuSourceFocusId = `search-${search.category}-${track.id}`; }" />
      <ContentGrid v-else-if="!search.isSongCategory && search.results.items.length" :cards="search.results.items" :group="`search-${search.category}`"
        :columns="4" square :up-focus-id="tabFocus" :down-focus-id="search.error ? 'pagination-retry' : search.results.nextCursor ? 'pagination-load-more' : undefined" left-focus-id="nav-search" @select="openCard" />
      <div v-else class="empty-state">
        <TvIcon :name="search.error ? 'refresh' : 'search'" :size="44" /><h2>{{ search.requiresLogin ? '登录后搜索音乐' : search.error ? '搜索暂时不可用' : '没有找到相关内容' }}</h2>
        <p>{{ search.requiresLogin ? `搜索${labels[search.category]}需要登录。扫码后将继续当前搜索，也可以先查看歌手类别。` : search.error ? '请检查网络连接后重试' : '换一个关键词，或试试其他类别' }}</p>
        <button v-if="search.requiresLogin" v-tv-focus type="button" data-focus-id="search-login" :data-focus-up="tabFocus" data-focus-left="nav-search" data-focus-right="search-retry" @click="requestLogin()">扫码登录</button>
        <button v-if="search.error" v-tv-focus type="button" data-focus-id="search-retry" :data-focus-up="tabFocus" :data-focus-left="search.requiresLogin ? 'search-login' : 'nav-search'" data-focus-right="search-retry" @click="tvFocusRegistry.restore('search-submit'); runAndEnter(() => search.retry())">重试搜索</button>
        <button v-else v-tv-focus type="button" data-focus-id="search-edit" :data-focus-up="tabFocus" data-focus-left="nav-search" @click="showSuggestions()">修改关键词</button>
      </div>
      <PaginationStatus v-if="search.results.items.length" :loading="search.loading" :has-more="Boolean(search.results.nextCursor)" :error="search.error"
        :focus-context="`${search.submittedQuery}:${search.category}`"
        :item-focus-ids="search.isSongCategory ? songCards.map(track => `search-${search.category}-${track.id}`) : search.results.items.map((_, index) => `search-${search.category}-${index}`)"
        left-focus-id="nav-search" :up-focus-id="`search-${search.category}-${search.isSongCategory ? search.results.items[search.results.items.length - 1].id : search.results.items.length - 1}`" @load-more="loadMore()" @retry="loadMore()" />
    </template>
    <SongActions :track="menuTrack" :source-focus-id="menuSourceFocusId" @close="menuTrack = null" />
  </section>
</template>

<style scoped>
.search-page { padding:12px 22px var(--tv-content-bottom-safe); min-width:0; }
.page-header { padding:10px 4px 20px; }.page-header h1 { margin:8px 0; font-size:54px; line-height:1.1; letter-spacing:-1.5px; }
.eyebrow { color:var(--tv-accent); font-size:16px; letter-spacing:3px; font-weight:650; }
.page-header p { margin:12px 0 0; color:var(--tv-text-secondary); font-size:22px; }
.search-bar { display:flex; gap:20px; padding:6px; }
.input-wrap { display:flex; flex:1; min-width:0; align-items:center; position:relative; }
.input-wrap > .tv-icon { position:absolute; left:26px; color:var(--tv-text-secondary); }
.search-bar input { --tv-focus-scale:1; min-width:0; width:100%; height:80px; padding:0 80px 0 76px; border:1px solid var(--tv-border); border-radius:20px; color:var(--tv-text); font-size:26px; background:#ffffff0b; outline:none; }
.search-bar input:focus { border-color:var(--tv-focus-color,#fff); background:#ffffff18; }
.clear-query { position:absolute; right:12px; display:grid; place-items:center; width:52px; height:52px; border:0; border-radius:12px; background:transparent; color:var(--tv-text-secondary); }
.submit { min-width:148px; height:80px; border:0; border-radius:20px; font-size:26px; font-weight:650; color:#201d25; background:#f6f3f8; }
.remote-hint { margin:10px 8px 18px; color:var(--tv-text-secondary); font-size:18px; }
.category-tabs { display:flex; gap:16px; padding:8px 6px 24px; }
.tab { min-width:98px; height:60px; padding:0 26px; border:0; border-radius:16px; color:var(--tv-text-secondary); font-size:25px; font-weight:600; background:transparent; }
.tab.active { color:var(--tv-text); background:#ffffff1f; }
.term-section { margin-top:22px; }.term-section h2 { display:flex; align-items:center; gap:12px; margin:0 6px 18px; font-size:27px; }
.term-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:16px; padding:4px; }
.term-row { display:flex; align-items:center; gap:16px; height:72px; padding:0 24px; border:1px solid transparent; border-radius:16px; color:var(--tv-text); font-size:23px; text-align:left; background:#ffffff08; min-width:0; }
.term-row span:last-child { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.term-number { color:var(--tv-text-secondary); font-size:19px; }
.term-row:focus { background:var(--tv-card-focus); }.term-row:focus .term-number { color:inherit; }
.results-heading { display:flex; align-items:center; gap:20px; margin:10px 8px 6px; font-size:27px; }
.results-heading span { color:var(--tv-text-secondary); font-size:21px; font-weight:400; }
.empty-state { padding:40px 26px; border-radius:24px; background:#ffffff05; color:var(--tv-text-secondary); }.empty-state h2 { color:var(--tv-text); font-size:29px; }.empty-state p { font-size:22px; }
.empty-state button { height:60px; margin:8px 16px 0 0; padding:0 28px; border:1px solid var(--tv-border); border-radius:16px; color:var(--tv-text); background:var(--tv-glass); font-size:23px; }
.page-header { display:flex; align-items:baseline; gap:24px; margin-bottom:22px; }.page-header .eyebrow { display:none; }.page-header h1 { margin:0; font-size:46px; }.page-header p { margin:0; font-size:22px; }.remote-hint { margin:14px 4px; }
.submit { display:inline-flex; align-items:center; justify-content:center; gap:10px; padding:0 20px; }
.hot-retry { margin:24px 6px; padding:16px 24px; min-height:64px; border:1px solid var(--tv-border); border-radius:16px; font-size:22px; color:var(--tv-text); background:var(--tv-glass); }
.term-section h2 button { min-height:60px; font-size:22px; }
</style>
