<script setup lang="ts">
import ContentGrid from './ContentGrid.vue';
import type { TvContentCard } from '../native/contentTypes';

defineProps<{
  title: string;
  cards: TvContentCard[];
  group: string;
  emptyText?: string;
}>();

const emit = defineEmits<{ select: [card: TvContentCard, event: Event] }>();
</script>

<template>
  <section class="content-shelf" :aria-labelledby="`${group}-title`">
    <div class="shelf-heading">
      <h2 :id="`${group}-title`">{{ title }}</h2>
    </div>
    <ContentGrid v-if="cards.length" :cards="cards.slice(0, 10)" :group="group" @select="(card, event) => emit('select', card, event)" />
    <p v-else class="empty">{{ emptyText ?? '暂无内容' }}</p>
  </section>
</template>

<style scoped>
.content-shelf { margin-top: 25px; }
.shelf-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 13px; }
.shelf-heading h2 { margin: 0; font-size: 24px; }
.empty { padding: 14px 8px; color: var(--tv-text-secondary); font-size: 15px; }
</style>
