<script setup lang="ts">
import { computed, ref } from 'vue';
import { usePaginationFocus } from './paginationFocus';

const props = defineProps<{
  loading: boolean;
  hasMore: boolean;
  error: boolean;
  upFocusId?: string;
  leftFocusId?: string;
  itemFocusIds?: string[];
  focusContext?: string;
}>();

const emit = defineEmits<{ loadMore: []; retry: [] }>();
const element = ref<HTMLElement | null>(null);
const upTarget = computed(() => props.itemFocusIds?.[props.itemFocusIds.length - 1] ?? props.upFocusId);
const { retainAction, begin } = usePaginationFocus({
  scope: () => element.value?.closest<HTMLElement>('[data-page]') ?? element.value?.parentElement ?? null,
  itemFocusIds: () => props.itemFocusIds ?? [],
  loading: () => props.loading,
  error: () => props.error,
  hasMore: () => props.hasMore,
  context: () => props.focusContext,
  fallbackFocusId: () => props.upFocusId,
});

function activate(event: MouseEvent): void {
  if (props.loading || (!props.error && !props.hasMore)) return;
  begin(event.currentTarget as HTMLElement);
  if (props.error) emit('retry');
  else emit('loadMore');
}
</script>

<template>
  <div ref="element" class="pagination-status" aria-live="polite">
    <button
      v-if="error || hasMore || loading || retainAction"
      v-tv-focus
      type="button"
      :data-focus-id="error ? 'pagination-retry' : 'pagination-load-more'"
      :data-focus-up="upTarget" :data-focus-left="leftFocusId"
      :aria-busy="loading"
      @focus="loading && itemFocusIds?.length === 0 && begin($event.target as HTMLElement)"
      @click="activate"
    >{{ loading ? '加载中…' : error ? '加载失败，按确认重试' : hasMore ? '加载更多' : '已全部加载' }}</button>
    <span v-else class="note" aria-label="已全部加载">已全部加载</span>
  </div>
</template>

<style scoped>
.pagination-status { display: flex; justify-content: center; padding: 22px 0 8px; }
.pagination-status button { min-width: 220px; height:60px; padding:0 28px; border:1px solid var(--tv-border); border-radius:16px; color:var(--tv-text); font-size:23px; background:var(--tv-glass); }
.pagination-status button[aria-busy="true"] { opacity: 0.6; }
.note { color: var(--tv-text-secondary); font-size:20px; }
</style>
