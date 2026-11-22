<script setup lang="ts">
import { computed } from 'vue';
import { TV_ICON_PATHS, TV_SOLID_ICONS, type TvIconName } from './icons';
const props = withDefaults(defineProps<{ name: string; size?: number | string; active?: boolean }>(), { size: 24, active: false });
const glyph = computed(() => Object.prototype.hasOwnProperty.call(TV_ICON_PATHS, props.name) ? props.name as TvIconName : 'music');
const solid = computed(() => TV_SOLID_ICONS.has(glyph.value) || (glyph.value === 'heart' && props.active));
</script>

<template>
  <svg class="tv-icon" :data-icon="glyph" :data-icon-paint="solid ? 'solid' : 'outline'" :width="size" :height="size" viewBox="0 0 24 24" :fill="solid ? 'currentColor' : 'none'" :stroke="solid ? 'none' : 'currentColor'" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path v-for="(path, index) in TV_ICON_PATHS[glyph]" :key="index" :d="path" />
  </svg>
</template>

<style scoped>
.tv-icon { display:inline-block; flex:none; vertical-align:middle; pointer-events:none; }
</style>
