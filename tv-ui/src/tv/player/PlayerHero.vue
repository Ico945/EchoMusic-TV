<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed } from 'vue';
import type { PlayerTrack } from '../stores/player';
import type { PlayerDisplayMode } from './LyricModeSwitch.vue';

const props = defineProps<{ track: PlayerTrack; mode: PlayerDisplayMode; quality: string; immersive?: boolean }>();
const artists = computed(() => props.track.artists.join('、'));
const qualityLabel = computed(() => {
  const labels: Record<string, string> = { '128': '标准音质', '320': '高品音质', flac: '无损音质', hires: 'Hi-Res', local: '本地音频' };
  return labels[props.quality.toLowerCase()] ?? (props.quality || '自动音质');
});
</script>

<template>
  <section class="player-hero" :class="[`mode-${mode}`, { 'is-immersive': immersive }]" data-player-hero>
    <div class="artwork-wrap">
      <img v-if="track.artworkUrl" :src="track.artworkUrl" alt="" class="artwork" />
      <div v-else class="artwork artwork-fallback" aria-hidden="true"><TvIcon name="music" :size="110" /></div>
      <span class="artwork-shine" aria-hidden="true" />
    </div>
    <div :key="track.id" class="track-copy tv-track-enter">
      <span>正在播放</span>
      <h1>{{ track.title }}</h1>
      <h2>{{ artists }}</h2>
      <p>{{ track.album || '未知专辑' }} · {{ qualityLabel }}</p>
    </div>
  </section>
</template>

<style scoped>
.player-hero { min-width:0; height:100%; display:flex; flex-direction:column; justify-content:center; gap:26px; padding:8px 26px 8px 14px; }
.artwork-wrap { position:relative; width:min(100%,470px); aspect-ratio:1; align-self:center; filter:drop-shadow(0 32px 52px rgba(0,0,0,.48)); transition:transform 240ms cubic-bezier(.2,.7,.2,1); }
.artwork { width:100%; height:100%; display:block; object-fit:cover; border:1px solid rgba(255,255,255,.16); border-radius:30px; background:linear-gradient(145deg,#176ab5,#684eb0 55%,#dd5b79); }
.artwork-fallback { display:grid; place-items:center; }.artwork-fallback span { font-size:92px; color:rgba(255,255,255,.82); }
.artwork-shine { position:absolute; inset:1px; border-radius:29px; background:linear-gradient(125deg,rgba(255,255,255,.2),transparent 24%,transparent 72%,rgba(255,255,255,.05)); pointer-events:none; }
.track-copy { max-width:560px; transition:opacity 260ms ease,transform 260ms ease,filter 260ms ease; }.track-copy>span { color:#82c0ff; font-size:14px; letter-spacing:2px; font-weight:760; }.track-copy h1 { margin:8px 0 5px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:44px; line-height:1.06; letter-spacing:-1.4px; }.track-copy h2 { margin:0; color:rgba(255,255,255,.76); font-size:24px; font-weight:560; }.track-copy p { margin:11px 0 0; color:rgba(255,255,255,.52); font-size:16px; }
.is-immersive .artwork-wrap { transform:translateY(-8px) scale(1.06); }
.mode-cover .artwork-wrap { width:min(100%,510px); }.mode-cover .track-copy { text-align:center; align-self:center; }
.mode-portrait .artwork-wrap { width:min(100%,520px); aspect-ratio:1.35; }.mode-portrait .artwork { filter:saturate(.86) contrast(.94); }.mode-portrait .track-copy { position:relative; margin-top:-122px; padding:52px 28px 24px; width:min(100%,520px); align-self:center; border-radius:0 0 30px 30px; background:linear-gradient(transparent,rgba(5,9,15,.92)); }
.player-hero { padding:0; gap:28px; }.artwork { border-radius:20px; }.artwork-shine { border-radius:20px; }.track-copy { align-self:center; width:min(100%,500px); }.track-copy>span { display:none; }.track-copy h1 { font-size:38px; }.track-copy p { font-size:20px; }
.is-immersive .track-copy { transform:translateY(10px); }.is-immersive .track-copy p { opacity:0; }
.track-copy p { transition:opacity 220ms ease; }
.mode-cover { width:740px; max-width:100%; }.mode-cover .artwork-wrap { width:480px; }.mode-cover.is-immersive .artwork-wrap { transform:translateY(-10px) scale(1.1); }.mode-cover .track-copy { text-align:center; }
@media (prefers-reduced-motion:reduce) { .artwork-wrap,.track-copy { transition:none; } }
</style>
