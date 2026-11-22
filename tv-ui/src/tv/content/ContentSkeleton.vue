<script setup lang="ts">
withDefaults(defineProps<{
  variant?: 'cards' | 'songs' | 'terms' | 'shelf';
  count?: number;
  columns?: number;
  heading?: boolean;
  round?: boolean;
  label?: string;
}>(), { variant: 'cards', count: 4, columns: 4, heading: false, label: '正在加载内容' });
</script>

<template>
  <div class="content-skeleton" :class="[`skeleton-${variant}`, { round }]" :data-skeleton="variant" role="status" aria-live="polite" :aria-label="label" :style="{ '--skeleton-columns': columns }">
    <span class="sr-only">{{ label }}</span>
    <div v-if="heading" class="skeleton-heading bone" aria-hidden="true" />
    <div class="skeleton-items" aria-hidden="true">
      <div v-for="index in count" :key="index" class="skeleton-item">
        <span v-if="variant === 'songs'" class="skeleton-index bone" />
        <span v-if="variant !== 'terms'" class="skeleton-art bone" />
        <span class="skeleton-copy"><span class="skeleton-title bone" /><span v-if="variant !== 'terms'" class="skeleton-meta bone" /></span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.content-skeleton { padding:4px; }
.sr-only { position:absolute; width:1px; height:1px; padding:0; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; }
.bone { display:block; background:#ffffff10; border-radius:8px; animation:skeleton-breathe 1.8s ease-in-out infinite alternate; }
.skeleton-heading { width:220px; height:35px; margin:8px 6px 12px; }
.skeleton-items { display:grid; grid-template-columns:repeat(var(--skeleton-columns),minmax(0,1fr)); gap:30px 24px; }
.skeleton-item { min-width:0; padding:8px; border-radius:22px; }
.skeleton-art { aspect-ratio:1; border-radius:16px; }
.round .skeleton-art { border-radius:50%; }.round .skeleton-copy > span { margin-left:auto; margin-right:auto; }
.skeleton-copy { display:block; min-width:0; }
.skeleton-title { height:29px; width:76%; margin:16px 2px 6px; }
.skeleton-meta { height:24px; width:48%; margin:0 2px 6px; opacity:.65; }
.skeleton-shelf .skeleton-items { grid-auto-flow:column; grid-template-columns:none; grid-auto-columns:calc((100% - 84px)/4); overflow:hidden; gap:24px; padding:14px 10px 20px; }
.skeleton-songs { padding:4px 0; }
.skeleton-songs .skeleton-items { display:flex; flex-direction:column; gap:10px; padding:12px; }
.skeleton-songs .skeleton-item { display:flex; align-items:center; min-height:90px; padding:8px 14px; gap:16px; border:1px solid transparent; background:#ffffff08; border-radius:var(--tv-radius-card); }
.skeleton-songs .skeleton-index { width:30px; height:18px; flex:none; }
.skeleton-songs .skeleton-art { width:58px; height:58px; flex:none; border-radius:12px; }
.skeleton-songs .skeleton-copy { flex:1; }
.skeleton-songs .skeleton-title { width:36%; height:28px; margin:0 0 3px; }
.skeleton-songs .skeleton-meta { width:22%; margin:0; }
.skeleton-terms .skeleton-items { gap:16px; }
.skeleton-terms .skeleton-item { display:flex; align-items:center; height:72px; padding:0 24px; background:#ffffff08; border-radius:16px; }
.skeleton-terms .skeleton-copy { width:100%; }.skeleton-terms .skeleton-title { height:26px; margin:0; }
@keyframes skeleton-breathe { from { opacity:.5; } to { opacity:1; } }
@media (prefers-reduced-motion:reduce) { .bone { animation:none; } }
</style>
