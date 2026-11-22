<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, nextTick, onBeforeUnmount, onMounted, watch } from 'vue';
import SongList from '../content/SongList.vue';
import { recallFocus, rememberFocus } from '../content/routeMemory';
import { useInitialFocusRestore } from '../content/initialFocus';
import type { TvTrack } from '../stores/app';
import { useTvBrowseStore } from '../stores/browse';
import { useTvPlayerStore } from '../stores/player';
import { useTvAppStore } from '../stores/app';
import { tvFocusRegistry } from '../focus/directive';

const PAGE_KEY = 'daily-recommendations';
const restoreInitialFocus = useInitialFocusRestore('nav-home');
const browse = useTvBrowseStore();
const player = useTvPlayerStore();
const app = useTvAppStore();
let alive = true;
const requiresLogin = computed(() => browse.dailyErrorCode === 'AUTH_EXPIRED');
const defaultFocus = computed(() => browse.dailyStatus === 'ready' && browse.dailyTracks.length ? 'daily-play-all' : 'daily-retry');
const stateText = computed(() => browse.dailyStatus === 'loading' ? '正在准备今天的音乐…'
  : requiresLogin.value ? '需要登录后获取你的每日推荐'
    : browse.dailyStatus === 'error' ? '每日推荐暂时未能加载'
      : '今天暂时没有返回推荐歌曲');

async function retry(): Promise<void> {
  if (requiresLogin.value) { window.dispatchEvent(new CustomEvent('echo-tv-login-requested')); return; }
  if (browse.dailyStatus === 'loading') return;
  await browse.loadDaily();
  await nextTick();
  if (alive && (document.activeElement as HTMLElement)?.dataset.focusId === 'daily-retry') tvFocusRegistry.restore(defaultFocus.value);
}

const trackIds = computed(() => browse.dailyTracks.map((track) => track.id));

function playAll(): void {
  if (!trackIds.value.length) return;
  void player.playQueue(trackIds.value, 0).catch(() => undefined);
}

function playTrack(track: TvTrack): void {
  void player.playQueue(trackIds.value, Math.max(0, browse.dailyTracks.findIndex((item) => item.id === track.id))).catch(() => undefined);
}

onMounted(async () => {
  await browse.loadDaily();
  void nextTick(() => {
    restoreInitialFocus(browse.dailyStatus === 'ready' ? recallFocus(PAGE_KEY) ?? defaultFocus.value : defaultFocus.value);
  });
});

watch([() => app.session.status, () => app.session.displayName], () => { void browse.loadDaily(); });
onBeforeUnmount(() => { alive = false; rememberFocus(PAGE_KEY); });
</script>

<template>
  <section class="daily-page" data-page="daily-recommendations" :data-default-focus-id="defaultFocus">
    <header class="page-header">
      <span class="eyebrow">FOR YOU</span>
      <h1>每日推荐</h1>
      <p>按你的口味，每天更新。</p>
    </header>

    <div v-if="browse.dailyStatus === 'ready' && browse.dailyTracks.length" class="toolbar">
      <button
        v-tv-focus
        type="button"
        class="primary"
        data-focus-id="daily-play-all"
        data-focus-left="nav-home"
        :data-focus-down="browse.dailyTracks.length ? `daily-${browse.dailyTracks[0].id}` : undefined"
        :disabled="!browse.dailyTracks.length"
        @click="playAll"
      ><TvIcon name="play" /> 播放全部</button>
    </div>

    <SongList
      v-if="browse.dailyStatus === 'ready' && browse.dailyTracks.length"
      :tracks="browse.dailyTracks"
      group="daily"
      up-focus-id="daily-play-all"
      left-focus-id="nav-home"
      :menu-enabled="false"
      @play="playTrack"
    />
    <section v-else class="daily-state" aria-live="polite">
      <span aria-hidden="true"><TvIcon name="discover" :size="56" /></span><h2>{{ stateText }}</h2>
      <p v-if="browse.dailyErrorCode">诊断代码：{{ browse.dailyErrorCode }}</p>
      <button v-tv-focus type="button" data-focus-id="daily-retry" data-focus-left="nav-home"
        :aria-busy="browse.dailyStatus === 'loading'" @click="retry">
        {{ browse.dailyStatus === 'loading' ? '加载中…' : requiresLogin ? '扫码登录' : '重新加载' }}
      </button>
    </section>
  </section>
</template>

<style scoped>
.daily-page { padding: 4px 14px 150px; min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size: 44px; letter-spacing: -1.6px; }
.page-header p { margin: 10px 0 0; color: var(--tv-text-secondary); font-size: 17px; }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.toolbar { margin: 24px 0 18px; }
.toolbar button { min-width: 200px; height: 52px; border: 0; border-radius: 26px; font-size: 16px; font-weight: 700; color: #07111d; background: white; }
.toolbar button:disabled { opacity: 0.5; }
.empty { padding: 30px 8px; color: var(--tv-text-secondary); font-size: 16px; }
.daily-page > button { min-width: 260px; height: 52px; margin-top: 20px; border: 1px solid rgba(124, 188, 255, 0.35); border-radius: 26px; color: #dceeff; background: rgba(36, 102, 164, 0.3); }
.daily-state { min-height:460px; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:18px; }
.daily-state>span { font-size:64px; color:var(--tv-accent); }.daily-state h2 { font-size:32px; margin:0; }.daily-state p { color:var(--tv-text-secondary); font-size:18px; }
.daily-state button { min-width:220px; height:64px; border:0; border-radius:32px; background:#f5f1f5; color:#211d23; font:inherit; font-size:24px; }
</style>
