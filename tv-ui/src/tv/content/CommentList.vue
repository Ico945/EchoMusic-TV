<script setup lang="ts">
import PaginationStatus from './PaginationStatus.vue';
import { scrollReadingPanel } from './reading';
import type { TvComment } from '../native/contentTypes';

defineProps<{
  comments: TvComment[];
  loading: boolean;
  hasMore: boolean;
  error: boolean;
  emptyText?: string;
}>();

const emit = defineEmits<{ loadMore: []; retry: [] }>();

function formatTime(epochMs: number): string {
  const date = new Date(epochMs);
  if (Number.isNaN(date.getTime())) return '';
  const now = Date.now();
  const diff = now - epochMs;
  if (diff < 60_000) return '刚刚';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
</script>

<template>
  <section class="comment-list" aria-label="评论">
    <div v-if="comments.length" class="comments">
      <article v-for="(comment, index) in comments" :key="comment.id" v-tv-focus :data-focus-id="`comment-${comment.id}`"
        :data-focus-up="index > 0 ? `comment-${comments[index - 1].id}` : undefined"
        :data-focus-down="index + 1 < comments.length ? `comment-${comments[index + 1].id}` : error ? 'pagination-retry' : hasMore || loading ? 'pagination-load-more' : undefined"
        tabindex="0" class="comment" aria-label="评论，上下键阅读" @keydown="scrollReadingPanel">
        <span class="avatar" aria-hidden="true">{{ (comment.user.nickname || '?').slice(0, 1) }}</span>
        <div class="body">
          <div class="meta">
            <strong>{{ comment.user.nickname }}</strong>
            <small>{{ formatTime(comment.time) }} · {{ comment.likeCount }} 赞</small>
          </div>
          <p>{{ comment.content }}</p>
        </div>
      </article>
    </div>
    <p v-else-if="!loading && !error" class="empty">{{ emptyText ?? '还没有评论' }}</p>
    <PaginationStatus :loading="loading" :has-more="hasMore" :error="error" :item-focus-ids="comments.map(comment => `comment-${comment.id}`)" @load-more="emit('loadMore')" @retry="emit('retry')" />
  </section>
</template>

<style scoped>
.comment-list { padding: 4px 0; }
.comments { display: flex; flex-direction: column; gap: 14px; }
.comment { display: flex; gap: 16px; padding: 16px; border: 1px solid transparent; border-radius: var(--tv-radius-card); background: rgba(255, 255, 255, 0.03); }
.avatar { flex: none; width: 48px; height: 48px; display: grid; place-items: center; border-radius: 50%; font-size: 20px; color: white; background: linear-gradient(145deg, #2499ff, #7845c0); }
.body { min-width: 0; }
.meta { display: flex; align-items: baseline; gap: 12px; }
.meta strong { font-size: 16px; }
.meta small { color: var(--tv-text-secondary); font-size: 13px; }
.body p { margin: 8px 0 0; color: var(--tv-text); font-size: 16px; line-height: 1.6; overflow-wrap: anywhere; }
.empty { padding: 26px 8px; color: var(--tv-text-secondary); font-size: 16px; }
.comment { max-height:360px; overflow-y:auto; --tv-focus-scale:1; padding:22px; }
.meta strong { font-size:22px; }.meta small { font-size:19px; }.body p { font-size:23px; line-height:1.7; }
</style>
