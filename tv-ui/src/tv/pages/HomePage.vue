<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, nextTick, onBeforeUnmount, onMounted } from 'vue';
import { tvFocusRegistry } from '../focus/directive';
import { useRouter } from 'vue-router';
import { useTvAppStore, type TvTrack } from '../stores/app';
import { useTvContentStore } from '../stores/content';
import { useTvPlayerStore } from '../stores/player';
import { useTvBrowseStore } from '../stores/browse';
import MediaShelf from '../content/MediaShelf.vue';
import type { TvContentCard } from '../native/contentTypes';
import { sourceFocusFromEvent } from '../content/routeMemory';

const app = useTvAppStore();
const content = useTvContentStore();
const player = useTvPlayerStore();
const router = useRouter();
const browse = useTvBrowseStore();
let pageAlive = true;
onBeforeUnmount(() => { pageAlive = false; });
const collections = computed(() => ['newPlaylists'].flatMap(id => {
  const shelf = browse.shelves.find(item => item.id === id);
  return shelf && (shelf.cards.length || shelf.status !== 'ready') ? [shelf] : [];
}));
const quickLinks = [
  { id: 'daily-recommendations', label: '每日推荐', detail: '今天值得一听的音乐', icon: 'discover' },
  { id: 'history', label: '最近播放', detail: '接着上次的音乐时光', icon: 'history' },
  { id: 'favorites', label: '我喜欢的', detail: '回到你的音乐收藏', icon: 'heart' },
];

function openCollection(card: TvContentCard, event: Event): void {
  const name = { playlist: 'playlist-detail', album: 'album-detail', artist: 'artist-detail' }[card.kind as 'playlist' | 'album' | 'artist'];
  if (name) void router.push({ name, params: { id: card.detailId ?? card.id }, query: { sourceFocusId: sourceFocusFromEvent(event) } });
}

function adjacentShelf(shelfIndex: number, trackIndex: number, direction: -1 | 1): string {
  const shelf = content.home?.shelves[shelfIndex + direction];
  const track = shelf?.tracks[Math.min(trackIndex, shelf.tracks.length - 1)];
  return shelf && track ? `${shelf.id}-${track.id}` : direction === -1 ? 'home-quick-daily-recommendations' : collections.value.length ? `home-collection-${collections.value[0].id}-0` : 'mini-open';
}

function revealShelf(event: FocusEvent): void {
  const grid = event.currentTarget as HTMLElement;
  const slot = (event.target as HTMLElement).closest<HTMLElement>('.music-card-slot');
  if (slot) grid.scrollLeft = Math.max(0, slot.offsetLeft - (grid.clientWidth - slot.offsetWidth) / 2);
}

function play(track: TvTrack) {
  const home = content.home;
  if (!home) return;
  const unique = [home.hero, ...home.secondary, ...home.shelves.flatMap((shelf) => shelf.tracks)].filter(
    (candidate, index, all) => all.findIndex((item) => item.id === candidate.id) === index,
  );
  const startIndex = unique.findIndex((candidate) => candidate.id === track.id);
  if (startIndex >= 0) void player.playQueue(unique.map((candidate) => candidate.id), startIndex).catch(() => undefined);
}

async function retry() {
  if (content.status === 'loading') return;
  const ownsFocus = (document.activeElement as HTMLElement | null)?.dataset.focusId === 'home-retry';
  await content.loadHome();
  await nextTick();
  const active = document.activeElement as HTMLElement | null;
  if (pageAlive && ownsFocus && content.home && (!active?.dataset.focusId || active.dataset.focusId === 'home-retry')) tvFocusRegistry.restore('hero-play');
}

function openMv(track: TvTrack, shelfId: string) {
  if (!track.mv) return;
  const sourceFocusId = `mv-${shelfId}-${track.id}`;
  void router.push({
    name: 'mv-detail',
    params: { id: track.mv.id },
    query: {
      title: track.mv.title,
      artist: track.mv.artist,
      quality: track.mv.quality,
      qualities: track.mv.qualities.join(','),
      sourceFocusId,
    },
  });
}

onMounted(async () => {
  if (!browse.loaded) void browse.loadExplore();
  const snapshot = await app.loadSnapshot();
  if (!pageAlive) return;
  await content.loadHome(snapshot.bootstrapHome);
});
</script>

<template>
  <section class="home-page core-browse-page" data-page="home">
    <header class="home-header">
      <div><span class="eyebrow">LISTEN NOW</span><h1>现在就听</h1></div>
      <p>{{ content.home?.greeting ?? '让音乐填满这一刻' }}</p>
    </header>

    <template v-if="content.home">
      <article class="hero">
        <div class="hero-glow" />
        <div class="hero-copy">
          <span class="eyebrow">{{ app.session.status === 'loggedIn' ? '为你推荐' : '精选推荐' }} · KUGOU MUSIC</span>
          <h2>{{ content.home.hero.title }}</h2>
          <p>{{ content.home.hero.artist }} · {{ content.home.hero.album }}</p>
          <button v-tv-focus type="button" data-focus-id="hero-play" data-focus-left="nav-home" data-focus-down="home-quick-daily-recommendations" @click="play(content.home.hero)"><TvIcon name="play" /> 开始聆听</button>
        </div>
        <div class="hero-art" aria-hidden="true">
          <img v-if="content.home.hero.artwork" :src="content.home.hero.artwork" alt="">
          <TvIcon v-else name="music" :size="90" />
        </div>
      </article>

      <nav class="home-quick-links" aria-label="聆听方式">
        <button v-for="(link, index) in quickLinks" :key="link.id" v-tv-focus type="button"
          :data-focus-id="`home-quick-${link.id}`" data-focus-up="hero-play"
          :data-focus-left="index ? `home-quick-${quickLinks[index - 1].id}` : 'nav-home'"
          :data-focus-right="`home-quick-${quickLinks[Math.min(index + 1, quickLinks.length - 1)].id}`"
          :data-focus-down="adjacentShelf(-1, index, 1)" @click="router.push({ name: link.id, query: { sourceFocusId: `home-quick-${link.id}` } })">
          <span aria-hidden="true"><TvIcon :name="link.icon" :size="32" /></span><div><strong>{{ link.label }}</strong><small>{{ link.detail }}</small></div><i aria-hidden="true"><TvIcon name="chevronRight" :size="24" /></i>
        </button>
      </nav>

      <section v-for="(shelf, shelfIndex) in content.home.shelves" :key="shelf.id" class="shelf" :aria-labelledby="`${shelf.id}-title`">
        <div class="shelf-heading"><h2 :id="`${shelf.id}-title`">{{ shelf.title }}</h2><span>{{ shelf.tracks.length }} 首</span></div>
        <div class="shelf-grid" @focusin="revealShelf">
          <div v-for="(track, index) in shelf.tracks" :key="track.id" class="music-card-slot">
            <button
              v-tv-focus
              type="button"
              class="music-card tv-card"
              :data-focus-id="`${shelf.id}-${track.id}`"
              :data-focus-group="shelf.id"
              :data-focus-left="index === 0 ? 'nav-home' : `${shelf.id}-${shelf.tracks[index - 1].id}`"
              :data-focus-right="`${shelf.id}-${shelf.tracks[Math.min(index + 1, shelf.tracks.length - 1)].id}`"
              :data-focus-up="adjacentShelf(shelfIndex, index, -1)"
              :data-focus-down="track.mv ? `mv-${shelf.id}-${track.id}` : adjacentShelf(shelfIndex, index, 1)"
              @click="play(track)"
            >
              <span class="card-art" :class="`tone-${(index % 5) + 1}`">
                <img v-if="track.artwork" :src="track.artwork" alt="">
                <span v-else class="cover-fallback" aria-hidden="true"><TvIcon name="music" :size="80" /></span>
                <i><TvIcon name="play" :size="26" /></i>
              </span>
              <strong>{{ track.title }}</strong><small>{{ track.artist }}</small>
            </button>
            <button
              v-if="track.mv"
              v-tv-focus
              type="button"
              class="mv-entry"
              data-mv-entry
              :data-focus-id="`mv-${shelf.id}-${track.id}`"
              :data-focus-group="shelf.id"
              :data-focus-left="index === 0 ? 'nav-home' : `${shelf.id}-${shelf.tracks[index - 1].id}`"
              :data-focus-up="`${shelf.id}-${track.id}`"
              :data-focus-down="adjacentShelf(shelfIndex, index, 1)"
              @click="openMv(track, shelf.id)"
            >播放 MV</button>
          </div>
        </div>
      </section>
    </template>

    <section v-else class="home-state" :class="content.status === 'error' ? 'home-error' : 'home-loading'" aria-live="polite">
      <span>{{ content.status === 'error' ? '内容暂不可用' : '正在连接酷狗' }}</span>
      <h2>{{ content.status === 'error' ? content.errorMessage : '为你准备音乐…' }}</h2>
      <p v-if="content.status === 'error'">请检查电视网络与系统时间后重试。</p>
      <div v-else class="loading-line" />
      <button v-tv-focus type="button" data-focus-id="home-retry" data-focus-left="nav-home" :aria-busy="content.status === 'loading'" @click="retry">{{ content.status === 'loading' ? '正在加载…' : '重新加载' }}</button>
    </section>

    <section v-for="shelf in collections" :key="shelf.id" class="shelf collection-shelf">
      <div class="shelf-heading"><h2>{{ shelf.title }}</h2><button v-tv-focus class="browse-more" type="button" :data-focus-id="`home-more-${shelf.id}`" data-focus-left="nav-home" @click="router.push({ name: 'explore', query: { category: shelf.id } })">查看全部 <TvIcon name="chevronRight" :size="20" /></button></div>
      <MediaShelf v-if="shelf.cards.length" :cards="shelf.cards.slice(0, 12)" :group="`home-collection-${shelf.id}`" left-focus-id="nav-home" @select="openCollection" />
      <button v-else v-tv-focus type="button" class="browse-more shelf-retry" :data-focus-id="`home-collection-${shelf.id}-0`" data-focus-left="nav-home" :aria-busy="shelf.status === 'loading'" @click="browse.retryShelf(shelf.id)">{{ shelf.status === 'loading' ? '正在加载此栏目…' : '此栏目暂未加载，按确认重试' }}</button>
    </section>
  </section>
</template>

<style scoped>
.home-page { padding:12px 22px var(--tv-content-bottom-safe); min-width:0; }
.home-header { height:124px; display:flex; align-items:center; justify-content:space-between; padding:0 4px 16px; }
.home-header h1 { margin:8px 0 0; font-size:54px; line-height:1.1; letter-spacing:-1.5px; }
.home-header p { color:var(--tv-text-secondary); font-size:23px; }
.home-quick-links { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:22px; margin:26px 8px 12px; }
.home-quick-links button { min-width:0; display:flex; align-items:center; gap:20px; min-height:110px; padding:18px 26px; border:1px solid var(--tv-border); border-radius:20px; background:var(--tv-glass); color:var(--tv-text); text-align:left; }
.home-quick-links button>span { font-size:40px; color:var(--tv-accent); }.home-quick-links strong { display:block; font-size:25px; }.home-quick-links small { display:block; margin-top:8px; color:var(--tv-text-secondary); font-size:20px; }.home-quick-links i { margin-left:auto; font-size:28px; font-style:normal; }
.browse-more { min-height:52px; padding:0 20px; border:0; border-radius:14px; background:var(--tv-glass); color:var(--tv-text-secondary); font-size:21px; }.shelf-retry { margin:14px 8px; }
.eyebrow { color:var(--tv-accent); font-size:16px; letter-spacing:3px; font-weight:650; }
.hero { height:340px; position:relative; overflow:hidden; border-radius:28px; background:linear-gradient(108deg,#392833,#252329 65%,#25282a); display:flex; align-items:center; border:1px solid #ffffff0d; }
.hero-glow { display:none; }
.hero-copy { position:relative; padding:24px 46px; z-index:2; width:calc(100% - 330px); min-width:0; }
.hero-copy h2 { margin:18px 0 10px; font-size:50px; line-height:1.18; letter-spacing:-1px; display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; overflow:hidden; }
.hero-copy p { margin:0 0 26px; color:#d0c7cf; font-size:23px; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
.hero-copy button { min-width:210px; height:62px; border:0; border-radius:16px; background:#f5f1f5; color:#231d23; font-size:23px; font-weight:650; }
.hero-art { position:absolute; right:24px; width:292px; height:292px; border-radius:16px; overflow:hidden; display:grid; place-items:center; background:linear-gradient(145deg,#725361,#36323b); box-shadow:0 24px 44px #0006; }
.hero-art img,.card-art img { width:100%; height:100%; display:block; object-fit:cover; }
.hero-art span { font-size:100px; color:#ffffffa6; }
.shelf { margin-top:34px; }
.shelf-heading { display:flex; align-items:center; justify-content:space-between; margin:0 6px 16px; }
.shelf-heading h2 { margin:0; font-size:29px; }.shelf-heading span { color:var(--tv-text-secondary); font-size:20px; }
.shelf-grid { position:relative; display:grid; grid-auto-flow:column; grid-auto-columns:calc((100% - 84px) / 4); gap:24px; padding:14px 10px; overflow-x:auto; scrollbar-width:none; }
.shelf-grid::-webkit-scrollbar { display:none; }
.music-card-slot { min-width:0; position:relative; display:flex; flex-direction:column; gap:14px; }
.music-card { width:100%; min-width:0; padding:7px; border:0; border-radius:22px; color:var(--tv-text); text-align:left; background:transparent; }
.music-card:focus { background:var(--tv-card-focus); }
.mv-entry { min-height:54px; margin:0 7px; border:1px solid var(--tv-border); border-radius:14px; color:var(--tv-text); background:var(--tv-glass); font-size:20px; }
.music-card strong,.music-card small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.music-card strong { margin:16px 3px 6px; font-size:24px; }.music-card small { margin:0 3px 8px; color:var(--tv-text-secondary); font-size:20px; }
.card-art { position:relative; aspect-ratio:1; overflow:hidden; border-radius:16px; display:grid; place-items:center; box-shadow:0 14px 28px #0005; }
.card-art i { position:absolute; right:16px; bottom:16px; opacity:0; width:56px; height:56px; border-radius:50%; background:#f5f1f5; color:#211c22; display:grid; place-items:center; font-style:normal; font-size:24px; }
.music-card:focus .card-art i { opacity:1; }
.cover-fallback { font-size:76px; color:#ffffff70; }
.tone-1 { background:linear-gradient(135deg,#49595f,#252b32); }.tone-2 { background:linear-gradient(135deg,#764e5a,#30212c); }.tone-3 { background:linear-gradient(135deg,#605568,#24222b); }.tone-4 { background:linear-gradient(135deg,#3e5954,#242c2a); }.tone-5 { background:linear-gradient(135deg,#71644c,#302b23); }
.home-state { min-height:340px; padding:52px 46px; border:1px solid var(--tv-border); border-radius:28px; background:var(--tv-glass); }
.home-state>span { color:var(--tv-accent); font-size:20px; }.home-state h2 { max-width:820px; margin:18px 0; font-size:42px; line-height:1.2; }
.home-state p { color:var(--tv-text-secondary); font-size:23px; }.home-state button { min-width:210px; height:62px; border:0; border-radius:16px; color:#231d23; background:#f5f1f5; font-size:23px; }
.loading-line { width:360px; height:6px; margin-top:36px; border-radius:8px; background:#ffffff16; }.loading-line::after { content:""; display:block; width:42%; height:100%; border-radius:inherit; background:var(--tv-accent); }
.hero { height:300px; }.hero-art { width:248px; height:248px; }
</style>
