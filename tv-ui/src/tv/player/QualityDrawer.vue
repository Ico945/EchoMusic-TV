<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
defineProps<{ qualities: string[]; selected: string; pending?: string | null; message?: string }>();
const labels: Record<string, string> = { '128': '标准', '320': '高品质', flac: '无损', hires: 'Hi-Res', standard: '标准', high: '高品质', lossless: '无损' };
const emit = defineEmits<{ close: []; select: [quality: string] }>();
</script>

<template>
  <aside class="player-drawer" role="dialog" aria-modal="true" aria-label="音质选择">
    <header><h2>音质</h2><button v-tv-focus type="button" data-focus-id="quality-close" @click="emit('close')"><TvIcon name="close" />关闭</button></header>
    <p v-if="qualities.length === 0">当前歌曲没有可用音质。</p>
    <p v-else-if="qualities.length === 1">当前歌曲仅提供这一种可用音质。</p>
    <p v-else>按确认切换；可用音质由歌曲资源与账号权限决定。</p>
    <div class="choices"><button v-for="quality in qualities" :key="quality" v-tv-focus type="button" :data-focus-id="`quality-${quality}`" :aria-pressed="quality === selected" :aria-busy="pending === quality" @click="!pending && quality !== selected && emit('select', quality)"><TvIcon name="quality" :size="28" /><span class="choice-copy"><strong>{{ labels[quality] ?? quality }}</strong><small>{{ pending === quality ? '切换中' : quality === selected ? '当前' : '按确认切换' }}</small></span><TvIcon v-if="quality === selected" name="check" :size="24" /></button></div>
    <p v-if="message" role="status">{{ message }}</p>
  </aside>
</template>

<style scoped>
.player-drawer { width:560px; max-width:100%; padding:32px; border:1px solid rgba(255,255,255,.18); border-radius:30px; color:#fff; background:rgba(17,27,42,.9); -webkit-backdrop-filter:blur(28px) saturate(130%); backdrop-filter:blur(28px) saturate(130%); box-shadow:0 36px 100px rgba(0,0,0,.58),inset 0 1px rgba(255,255,255,.1); } header { display:flex; justify-content:space-between; align-items:center; } h2,p { margin:0; } h2 { font-size:32px; } header button,.choices button { border:0; color:#fff; background:rgba(255,255,255,.12); } header button { min-width:104px; height:50px; border-radius:25px; font-size:16px; } p { margin-top:22px; color:rgba(255,255,255,.62); font-size:17px; } .choices { display:flex; flex-wrap:wrap; gap:14px; margin-top:24px; } .choices button { min-width:118px; height:58px; border-radius:18px; font-size:17px; font-weight:720; } .choices button[aria-pressed="true"] { background:#489bff; }
.player-drawer { background:rgba(32,29,35,.97); } header button,.choices button { font-size:24px; min-height:66px; }.choices button[aria-pressed=true] { background:var(--tv-accent); color:#211b20; }
.choices { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px; }
.choices button { display:flex; align-items:center; gap:12px; height:98px; padding:16px; text-align:left; }
.choice-copy { display:flex; flex:1; min-width:0; flex-direction:column; gap:5px; }
.choice-copy strong { font-size:24px; font-weight:650; white-space:nowrap; }.choice-copy small { font-size:17px; opacity:.72; white-space:nowrap; }
header button { display:inline-flex; align-items:center; justify-content:center; gap:8px; padding:0 16px; }
</style>
