<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { ref } from 'vue';
import type { TvContentCard } from '../native/contentTypes';

defineProps<{
  cards: TvContentCard[];
  group: string;
  emptyText?: string;
  leftFocusId?: string;
  columns?: number;
  square?: boolean;
  upFocusId?: string;
  downFocusId?: string;
}>();

const brokenImages = ref(new Set<string>());
const emit = defineEmits<{ select: [card: TvContentCard, event: Event] }>();
</script>

<template>
  <section class="content-grid" :class="{ square }" :aria-label="group">
    <div v-if="cards.length" class="grid" :style="columns ? { gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` } : undefined">
      <button
        v-for="(card, index) in cards"
        :key="card.id"
        v-tv-focus
        type="button"
        class="content-card tv-card"
        :class="{ artist: card.kind === 'artist' }"
        :data-focus-id="`${group}-${index}`"
        :data-focus-group="group"
        :data-focus-boundary-up="index < (columns ?? 5) || undefined"
        :data-focus-boundary-down="Math.floor(index / (columns ?? 5)) === Math.floor((cards.length - 1) / (columns ?? 5)) || undefined"
        :data-focus-left="leftFocusId && index % (columns ?? 5) === 0 ? leftFocusId : index > 0 ? `${group}-${index - 1}` : undefined"
        :data-focus-right="index % (columns ?? 5) === (columns ?? 5) - 1 || index === cards.length - 1 ? `${group}-${index}` : `${group}-${index + 1}`"
        :data-focus-up="index < (columns ?? 5) ? upFocusId : `${group}-${index - (columns ?? 5)}`"
        :data-focus-down="Math.floor(index / (columns ?? 5)) < Math.floor((cards.length - 1) / (columns ?? 5)) ? `${group}-${Math.min(index + (columns ?? 5), cards.length - 1)}` : downFocusId"
        @click="emit('select', card, $event)"
      >
        <span class="card-art" :class="`tone-${(index % 5) + 1}`" aria-hidden="true">
          <img v-if="card.artwork && !brokenImages.has(card.artwork)" :src="card.artwork" alt="" loading="lazy" @error="brokenImages.add(card.artwork)" />
          <TvIcon v-else :name="card.kind === 'artist' ? 'user' : card.kind === 'song' ? 'music' : card.kind === 'mv' ? 'play' : card.kind" :size="52" />
        </span>
        <strong>{{ card.title }}</strong>
        <small>{{ card.subtitle || ' ' }}</small>
        <em v-if="card.badge">{{ card.badge }}</em>
      </button>
    </div>
    <p v-else class="empty">{{ emptyText ?? '暂无内容' }}</p>
  </section>
</template>

<style scoped>
.content-grid { padding: 4px; }
.square .grid { gap:30px 24px; }.square .content-card { padding:8px; border:0; border-radius:22px; background:transparent; }.square .content-card:focus { background:var(--tv-card-focus); }.square .card-art { aspect-ratio:1; border-radius:16px; }.square .content-card strong { margin:16px 2px 6px; font-size:24px; }.square .content-card small { margin:0 2px 6px; font-size:20px; }
.grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 18px; }
.content-card { position: relative; min-width: 0; padding: 6px; border: 1px solid transparent; border-radius: var(--tv-radius-card); color: var(--tv-text); text-align: left; background: var(--tv-card-rest); }
.content-card:focus { border-color: var(--tv-card-focus-border); background: var(--tv-card-focus); }
.card-art { aspect-ratio: 1.55; display: grid; place-items: center; border-radius: var(--tv-radius-card); overflow: hidden; box-shadow: 0 16px 36px rgba(0, 0, 0, 0.26); }
.card-art img { width: 100%; height: 100%; object-fit: cover; }
.artist .card-art { border-radius:50%; }.artist { text-align:center; }
.card-art i { font-size: 40px; font-style: normal; opacity: 0.9; }
.content-card strong, .content-card small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.content-card strong { margin: 10px 3px 4px; font-size: var(--tv-card-title-size); }
.content-card small { margin: 0 3px; color: var(--tv-text-secondary); font-size: var(--tv-card-meta-size); }
.content-card em { position: absolute; top: 10px; right: 10px; padding: 3px 9px; border-radius: 8px; font-style: normal; font-size: 12px; color: #0b1420; background: linear-gradient(120deg, #ffd76e, #ffb35c); }
.tone-1 { background:linear-gradient(140deg,#4f424e,#29272f); }
.tone-2 { background:linear-gradient(140deg,#54515d,#2d2b34); }
.tone-3 { background:linear-gradient(140deg,#3c5057,#282b32); }
.tone-4 { background:linear-gradient(140deg,#445750,#242f2c); }
.tone-5 { background:linear-gradient(140deg,#615449,#302d29); }
.empty { padding: 26px 8px; color: var(--tv-text-secondary); font-size: 16px; }
</style>
