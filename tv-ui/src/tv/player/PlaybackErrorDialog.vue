<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, onMounted } from 'vue';
import type { PlayerCommandError } from '../stores/player';

const props = defineProps<{ error: PlayerCommandError }>();
const emit = defineEmits<{ action: [action: 'retry' | 'skip' | 'close']; opened: [] }>();
const retryLabel = computed(() => (
  props.error.code === 'PLAYBACK_NEXT_FAILED'
    ? '重试下一首'
    : props.error.code === 'PLAYBACK_PREVIOUS_FAILED'
      ? '重试上一首'
      : '重试当前歌曲'
));
onMounted(() => emit('opened'));
</script>

<template>
  <section class="playback-error" role="dialog" aria-modal="true" aria-labelledby="playback-error-title">
    <span>播放出现问题</span><h2 id="playback-error-title">{{ error.message }}</h2>
    <div>
      <button v-tv-focus type="button" data-focus-id="playback-error-retry" data-playback-error-action="retry" @click="emit('action', 'retry')"><TvIcon name="refresh" />{{ retryLabel }}</button>
      <button v-tv-focus type="button" data-focus-id="playback-error-skip" data-playback-error-action="skip" @click="emit('action', 'skip')"><TvIcon name="next" />跳到下一首</button>
      <button v-tv-focus type="button" data-focus-id="playback-error-close" data-playback-error-action="close" @click="emit('action', 'close')"><TvIcon name="close" />关闭</button>
    </div>
  </section>
</template>

<style scoped>
.playback-error { width:640px; max-width:100%; padding:40px; border:1px solid rgba(255,157,120,.38); border-radius:30px; color:#fff; background:rgba(48,27,36,.92); -webkit-backdrop-filter:blur(28px); backdrop-filter:blur(28px); box-shadow:0 36px 100px rgba(0,0,0,.62),inset 0 1px rgba(255,255,255,.08); } span { color:#ffb08d; font-size:15px; letter-spacing:1.7px; } h2 { margin:12px 0 28px; font-size:32px; line-height:1.25; } div { display:flex; flex-wrap:wrap; gap:14px; } button { min-width:150px; height:54px; border:0; border-radius:27px; color:#fff; background:rgba(255,255,255,.12); font-size:16px; font-weight:680; } button:first-child { background:#e27654; }
button { display:inline-flex; align-items:center; justify-content:center; gap:10px; padding:0 20px; }
</style>
