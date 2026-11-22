<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { onBeforeUnmount, ref, useTemplateRef } from 'vue';
import { cancelScrollMotion, scrollToPosition } from '../input/scrollMotion';
import type { TvContentCard } from '../native/contentTypes';

const props = defineProps<{
  cards: TvContentCard[];
  group: string;
  leftFocusId: string;
  upFocusId?: string | ((index: number) => string);
  downFocusId?: string | ((index: number) => string);
}>();
const emit = defineEmits<{ select: [card: TvContentCard, event: Event] }>();
const brokenImages = ref(new Set<string>());
const shelf = useTemplateRef<HTMLElement>('shelf');
onBeforeUnmount(() => { if (shelf.value) cancelScrollMotion(shelf.value); });
function reveal(event: FocusEvent): void {
  const row = event.currentTarget as HTMLElement;
  const card = (event.target as HTMLElement).closest<HTMLElement>('[data-focus-id]');
  if (!card) return;
  const start = row.scrollLeft;
  const left = card.offsetLeft;
  const right = left + card.offsetWidth;
  const animate = row.closest<HTMLElement>('.tv-canvas')?.dataset.remoteNavigating === 'true';
  cancelScrollMotion(row, 'left');
  if (left < start + 12) scrollToPosition(row, 'left', left - 12, animate);
  else if (right > start + row.clientWidth - 12) scrollToPosition(row, 'left', right - row.clientWidth + 12, animate);
}
</script>

<template>
  <div ref="shelf" class="media-shelf" :data-media-shelf="group" @focusin="reveal">
    <button v-for="(card, index) in cards" :key="card.id" v-tv-focus type="button"
      class="media-card tv-card" :class="{ artist: card.kind === 'artist' }"
      :data-focus-id="`${group}-${index}`" :data-focus-group="group"
      :data-focus-left="index === 0 ? leftFocusId : `${group}-${index - 1}`"
      :data-focus-right="`${group}-${Math.min(index + 1, cards.length - 1)}`"
      :data-focus-up="typeof upFocusId === 'function' ? upFocusId(index) : upFocusId" :data-focus-down="typeof downFocusId === 'function' ? downFocusId(index) : downFocusId"
      @click="emit('select', card, $event)">
      <span class="media-art" aria-hidden="true">
        <img v-if="card.artwork && !brokenImages.has(card.id)" :src="card.artwork" alt="" loading="lazy" @error="brokenImages.add(card.id)">
        <span v-else class="art-fallback"><TvIcon :name="card.kind === 'artist' ? 'user' : 'music'" :size="68" /></span>
        <i><TvIcon name="play" :size="26" /></i>
      </span>
      <strong>{{ card.title }}</strong><small>{{ card.subtitle || (card.kind === 'artist' ? '歌手' : card.kind === 'playlist' ? '歌单' : card.kind === 'album' ? '专辑' : '单曲') }}</small>
    </button>
  </div>
</template>

<style scoped>
.media-shelf { position:relative; display:grid; grid-auto-flow:column; grid-auto-columns:calc((100% - 84px)/4); gap:24px; overflow-x:auto; padding:14px 10px 20px; scrollbar-width:none; }
.media-shelf::-webkit-scrollbar { display:none; }
.media-card { min-width:0; padding:8px; border:0; border-radius:22px; color:var(--tv-text); text-align:left; background:transparent; }
.media-card:focus { background:var(--tv-card-focus); }
.media-art { position:relative; display:grid; place-items:center; aspect-ratio:1; border-radius:16px; overflow:hidden; background:linear-gradient(135deg,#48414f,#24252c); box-shadow:0 14px 30px #0005; }
.media-art img { width:100%; height:100%; object-fit:cover; }.art-fallback { font-size:76px; color:#ffffff60; }
.artist .media-art { border-radius:50%; }.artist { text-align:center; }
.media-art i { position:absolute; right:16px; bottom:16px; display:grid; place-items:center; width:54px; height:54px; border-radius:50%; color:#241f27; background:#faf7fc; font-size:23px; font-style:normal; opacity:0; }
.media-card:focus i { opacity:1; }.media-card strong,.media-card small { display:block; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
.media-card strong { margin:16px 2px 6px; font-size:24px; }.media-card small { margin:0 2px 6px; color:var(--tv-text-secondary); font-size:20px; }
</style>
