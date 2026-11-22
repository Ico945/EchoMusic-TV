<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { ref } from 'vue';
import { useTvPlayerStore } from '../stores/player';

const emit = defineEmits<{ close: [] }>();
const player = useTvPlayerStore();
const failedPreset = ref<string | null>(null);
const selected = ref<string | null>(null);
const busy = ref(false);
const labels: Record<string, string> = { off: '关闭音效', bass: '低音增强', vocal: '人声清晰', live: '现场' };
const icons = { off: 'power', bass: 'waveform', vocal: 'mic', live: 'radio' } as const;
const presets = ['off', 'bass', 'vocal', 'live'] as const;

async function select(preset: (typeof presets)[number]) {
  if (busy.value) return;
  busy.value = true;
  try {
    await player.setSoundEffect(preset);
    selected.value = preset;
    failedPreset.value = null;
  } catch {
    failedPreset.value = preset;
  } finally { busy.value = false; }
}
</script>

<template>
  <aside class="player-drawer" role="dialog" aria-modal="true" aria-label="本地音效">
    <header><h2>本地音效</h2><button v-tv-focus type="button" data-focus-id="sound-effect-close" @click="emit('close')"><TvIcon name="close" />关闭</button></header>
    <p>音效仅在当前 Android 设备播放会话中生效。</p>
    <div class="choices">
      <button v-for="preset in presets" :key="preset" v-tv-focus type="button" :data-focus-id="`sound-effect-${preset}`" :data-sound-effect-preset="preset" :aria-pressed="selected === preset" :aria-busy="busy" @click="select(preset)"><TvIcon :name="icons[preset]" :size="28" /><span class="choice-copy"><strong>{{ labels[preset] }}</strong><small>{{ selected === preset ? '已应用' : '按确认应用' }}</small></span><TvIcon v-if="selected === preset" name="check" :size="24" /></button>
    </div>
    <p v-if="failedPreset" data-sound-effect-error>音效未应用，请重试或选择其他音效</p>
  </aside>
</template>

<style scoped>
.player-drawer { width:560px; max-width:100%; padding:32px; border:1px solid rgba(255,255,255,.18); border-radius:30px; color:#fff; background:rgba(17,27,42,.9); -webkit-backdrop-filter:blur(28px) saturate(130%); backdrop-filter:blur(28px) saturate(130%); box-shadow:0 36px 100px rgba(0,0,0,.58),inset 0 1px rgba(255,255,255,.1); } header { display:flex; justify-content:space-between; align-items:center; } h2,p { margin:0; } h2 { font-size:32px; } header button,.choices button { border:0; color:#fff; background:rgba(255,255,255,.12); } header button { min-width:104px; height:50px; border-radius:25px; font-size:16px; } p { margin-top:20px; color:rgba(255,255,255,.62); font-size:17px; line-height:1.45; } .choices { display:flex; flex-wrap:wrap; gap:14px; margin-top:24px; } .choices button { min-width:108px; height:58px; border-radius:18px; font-size:17px; font-weight:720; text-transform:uppercase; } .choices button:disabled { color:rgba(255,255,255,.35); cursor:not-allowed; } [data-sound-effect-error] { color:#ffb08d; }
.player-drawer { background:rgba(32,29,35,.97); } header button,.choices button { font-size:24px; min-height:66px; }.choices button[aria-pressed=true] { background:var(--tv-accent); color:#211b20; }
.choices { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px; }
.choices button { display:flex; align-items:center; gap:12px; height:98px; padding:16px; text-align:left; }
.choice-copy { display:flex; flex:1; min-width:0; flex-direction:column; gap:5px; }
.choice-copy strong { font-size:24px; font-weight:650; white-space:nowrap; }.choice-copy small { font-size:17px; opacity:.72; white-space:nowrap; }
header button { display:inline-flex; align-items:center; justify-content:center; gap:8px; padding:0 16px; }
</style>
