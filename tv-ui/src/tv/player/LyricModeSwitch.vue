<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
export type PlayerDisplayMode = 'lyrics' | 'cover';

defineProps<{ modelValue: PlayerDisplayMode; lyricsAvailable?: boolean; lyricsVisible?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [mode: PlayerDisplayMode] }>();

const modes: Array<{ id: PlayerDisplayMode; label: string }> = [
  { id: 'lyrics', label: '歌词' },
  { id: 'cover', label: '封面' },
];
</script>

<template>
  <div class="mode-switch" aria-label="播放页显示模式">
    <button
      v-for="(mode, index) in modes"
      :key="mode.id"
      v-tv-focus
      type="button"
      :class="{ active: modelValue === mode.id }"
      :aria-pressed="modelValue === mode.id"
      :disabled="mode.id === 'lyrics' && lyricsAvailable === false"
      :data-focus-id="`mode-${mode.id}`"
      :data-focus-left="`mode-${modes[(index + modes.length - 1) % modes.length].id}`"
      :data-focus-right="`mode-${modes[(index + 1) % modes.length].id}`"
      :data-focus-down="lyricsVisible ? 'player-lyrics-panel' : 'player-progress'"
      @click="emit('update:modelValue', mode.id)"
    >
      <TvIcon :name="mode.id === 'lyrics' ? 'lyrics' : 'cover'" :size="24" />{{ mode.label }}
    </button>
  </div>
</template>

<style scoped>
.mode-switch { display:flex; align-items:center; gap:5px; padding:5px; border:1px solid rgba(255,255,255,.14); border-radius:21px; background:rgba(31,29,34,.8); -webkit-backdrop-filter:blur(20px); backdrop-filter:blur(20px); box-shadow:inset 0 1px rgba(255,255,255,.08); }
button { display:inline-flex; align-items:center; justify-content:center; gap:10px; min-width:84px; height:58px; padding:0 17px; border:0; border-radius:16px; color:rgba(255,255,255,.64); background:transparent; font-size:22px; font-weight:680; }
button.active { color:#07111d; background:rgba(255,255,255,.94); box-shadow:0 6px 18px rgba(0,0,0,.22); }
</style>
