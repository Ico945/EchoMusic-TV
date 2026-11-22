<script setup lang="ts">
import { ref, watch } from 'vue';
import TvIcon from '../components/TvIcon.vue';
const props = defineProps<{ artwork: string; index?: number }>();
const broken = ref(false);
watch(() => props.artwork, () => { broken.value = false; });
</script>

<template>
  <span class="ranking-artwork" :class="`chart-tone-${(index ?? 0) % 4}`" aria-hidden="true">
    <img v-if="artwork && !broken" :src="artwork" alt="" loading="lazy" @error="broken = true" />
    <span v-else class="chart-fallback"><TvIcon name="chart" :size="80" /><span>CHARTS</span></span>
  </span>
</template>

<style scoped>
.ranking-artwork { display:grid; place-items:center; aspect-ratio:1; border-radius:16px; overflow:hidden; background:linear-gradient(145deg,#795269,#302738); }
.ranking-artwork img { width:100%; height:100%; object-fit:cover; }
.chart-tone-1 { background:linear-gradient(145deg,#536b87,#262f46); }.chart-tone-2 { background:linear-gradient(145deg,#756649,#332e29); }.chart-tone-3 { background:linear-gradient(145deg,#497b70,#253a39); }
.chart-fallback { display:flex; flex-direction:column; align-items:center; gap:24px; color:#ffffffb3; }.chart-fallback span { font-size:16px; letter-spacing:5px; }
</style>
