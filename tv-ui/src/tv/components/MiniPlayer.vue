<script setup lang="ts">
import TvIcon from './TvIcon.vue';
import { useRouter } from 'vue-router';
import { computed, ref } from 'vue';
import { useTvPlayerStore } from '../stores/player';
import { useTransientNotice } from '../content/notice';

const player = useTvPlayerStore();
const router = useRouter();
const { notice, show } = useTransientNotice();
const pending = ref(false);
const canPrevious = computed(() => player.queueIndex > 0 || (player.repeatMode === 'all' && player.queue.length > 0));
const canNext = computed(() => player.queueIndex >= 0 && (player.queueIndex + 1 < player.queue.length || player.repeatMode === 'all'));
async function command(action: 'previous' | 'toggle' | 'next') {
  if (pending.value) return;
  if (action === 'previous' && !canPrevious.value) { show('已是第一首'); return; }
  if (action === 'next' && !canNext.value) { show('已是最后一首'); return; }
  pending.value = true;
  try { await player[action](); } catch { show('操作未成功，请重试'); }
  finally { pending.value = false; }
}
function open(queue = false) {
  void router.push({ name: 'now-playing', ...(queue ? { query: { panel: 'queue' } } : {}) });
}
</script>

<template>
  <aside class="mini-player" data-mini-player aria-label="播放条，菜单键快捷进入，返回键回到内容">
    <button v-tv-focus type="button" class="track-summary" data-focus-id="mini-open" data-focus-left="mini-open" data-focus-right="mini-previous" data-focus-down="mini-open"
      :aria-label="player.error || player.commandFailure ? '播放异常，按确认查看处理方式' : '打开全屏歌词'" @click="open()">
      <span class="mini-art"><img v-if="player.currentTrack?.artworkUrl" :src="player.currentTrack.artworkUrl" alt="" /><TvIcon v-else name="music" :size="30" /></span>
      <span class="mini-copy"><small :class="{ 'error-status': player.error || player.commandFailure }" role="status">{{ notice || (player.commandFailure ? '操作未完成 · 确认查看' : player.statusLabel) }} · {{ player.currentTrack?.artists.join('、') }}</small><strong>{{ player.currentTrack?.title }}</strong></span>
      <span class="lyric-entry"><TvIcon name="lyrics" :size="28" /><b>{{ player.error || player.commandFailure ? '查看问题' : '歌词' }}</b><small>按确认进入</small></span>
    </button>
    <button v-tv-focus type="button" class="skip-track" :data-unavailable="!canPrevious" :aria-label="canPrevious ? '上一首' : '上一首，已是第一首'" data-focus-id="mini-previous" data-focus-left="mini-open" data-focus-right="mini-toggle" data-focus-down="mini-previous" :aria-busy="pending" @click="command('previous')"><TvIcon name="previous" :size="30" /><span>上一首</span></button>
    <button v-tv-focus type="button" class="play-toggle" :aria-label="player.isPlaying ? '暂停' : '播放'" data-focus-id="mini-toggle" data-focus-left="mini-previous" data-focus-right="mini-next" data-focus-down="mini-toggle" :aria-busy="pending" @click="command('toggle')">
      <TvIcon :name="player.isPlaying ? 'pause' : 'play'" :size="28" /><span>{{ player.isPlaying ? '暂停' : '播放' }}</span>
    </button>
    <button v-tv-focus type="button" class="skip-track" :data-unavailable="!canNext" :aria-label="canNext ? '下一首' : '下一首，已是最后一首'" data-focus-id="mini-next" data-focus-left="mini-toggle" data-focus-right="mini-details" data-focus-down="mini-next" :aria-busy="pending" @click="command('next')"><TvIcon name="next" :size="30" /><span>下一首</span></button>
    <button v-tv-focus type="button" class="queue-entry" aria-label="打开待播队列" data-focus-id="mini-details" data-focus-left="mini-next" data-focus-right="mini-details" data-focus-down="mini-details" @click="open(true)">
      <TvIcon name="queue" :size="28" /><span>待播队列</span>
    </button>
    <span class="dock-hint">返回 · 内容</span>
    <span class="progress-track" role="progressbar" :aria-valuenow="Math.round(player.progressRatio * 100)" aria-valuemin="0" aria-valuemax="100"><i data-progress-fill :style="{ width: `${player.progressRatio * 100}%` }" /></span>
  </aside>
</template>

<style scoped>
.mini-player { min-width:0; height:112px; padding:12px 20px; display:flex; align-items:center; gap:20px; border:1px solid #ffffff17; border-radius:24px; background:rgba(30,27,32,.94); backdrop-filter:blur(20px); box-shadow:0 -12px 36px #0002; position:relative; z-index:8; }
.mini-player:focus-within { background:rgba(40,36,43,.98); border-color:#ffffff40; }
button { border:0; color:white; background:transparent; --tv-focus-scale:1; }
.track-summary { min-width:0; flex:1; height:82px; display:flex; align-items:center; gap:18px; border-radius:16px; padding:8px; text-align:left; }
.mini-art { width:60px; height:60px; flex:none; display:grid; place-items:center; border-radius:12px; background:#ffffff12; overflow:hidden; }.mini-art img { width:100%; height:100%; object-fit:cover; }
.mini-copy { display:flex; flex-direction:column; flex:1; gap:5px; min-width:0; }.mini-copy strong,.mini-copy small { white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }.mini-copy strong { font-size:25px; }.mini-copy small { font-size:19px; color:var(--tv-text-secondary); }
.lyric-entry { flex:none; display:flex; align-items:center; gap:12px; padding:0 24px; color:#f6f0f5; }.lyric-entry b { font-size:26px; }.lyric-entry small { color:var(--tv-text-secondary); font-size:19px; }.track-summary:focus { background:#ffffff12; }
.play-toggle,.queue-entry { flex:none; height:70px; padding:0 24px; display:flex; align-items:center; justify-content:center; gap:12px; border-radius:18px; font-size:22px; }.play-toggle { color:#221d24; background:#f5f1f5; }.queue-entry { background:#ffffff09; }
.dock-hint { flex:none; color:#aea5b1; font-size:17px; line-height:1.65; padding:0 10px; }
.progress-track { position:absolute; bottom:5px; left:28px; right:28px; height:2px; background:#ffffff10; overflow:hidden; }.progress-track i { display:block; height:100%; background:var(--tv-accent); }
</style>

<style scoped>
.mini-player { gap:12px; }
.lyric-entry { padding:0 14px; }.lyric-entry small { display:none; }
.skip-track { flex:none; height:70px; padding:0 18px; display:flex; align-items:center; gap:10px; border-radius:18px; background:#ffffff09; font-size:22px; }
.skip-track[data-unavailable=true] { color:var(--tv-text-secondary); }
</style>
