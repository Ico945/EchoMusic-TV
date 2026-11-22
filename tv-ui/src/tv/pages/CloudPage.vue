<script setup lang="ts">
import ContentSkeleton from '../content/ContentSkeleton.vue';
import TvIcon from '../components/TvIcon.vue';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import PaginationStatus from '../content/PaginationStatus.vue';
import { useTransientNotice } from '../content/notice';
import { recallFocus, rememberFocus } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import { useBackLayerRegistry } from '../input/backCoordinator';
import type { TvContentCard } from '../native/contentTypes';
import { useTvAppStore } from '../stores/app';
import { useTvLibraryStore, type LibrarySectionState } from '../stores/library';

const PAGE_KEY = 'library-cloud';
const app = useTvAppStore();
const library = useTvLibraryStore();
const router = useRouter();
const backLayers = useBackLayerRegistry();
const { notice, show } = useTransientNotice();

const pendingDelete = ref<TvContentCard | null>(null);
const loadingMore = ref(false);
let releaseConfirmLayer: (() => void) | undefined;

const loggedIn = computed(() => app.session.status === 'loggedIn');
const section = computed<LibrarySectionState>(() => library.sections.cloud ?? { items: [], nextCursor: null, total: null, status: 'loading' });

function requestLogin(): void {
  window.dispatchEvent(new CustomEvent('echo-tv-login-requested'));
}

function askDelete(card: TvContentCard): void {
  pendingDelete.value = card;
}

function cancelDelete(): void {
  pendingDelete.value = null;
}

async function confirmDelete(): Promise<void> {
  const target = pendingDelete.value;
  if (!target) return;
  const index = section.value.items.findIndex((card) => card.id === target.id);
  const ok = await library.deleteCloudItem(target.id);
  show(ok ? '已删除' : '删除失败，请重试');
  pendingDelete.value = null;
  if (ok) {
    const nextCard = section.value.items[index] ?? section.value.items[index - 1];
    void nextTick(() => tvFocusRegistry.restore(nextCard ? `cloud-${nextCard.id}` : 'cloud-retry'));
  } else {
    void nextTick(() => tvFocusRegistry.restore(`cloud-${target.id}`));
  }
}

async function loadMore(): Promise<void> {
  const cursor = section.value.nextCursor;
  if (!cursor || loadingMore.value) return;
  loadingMore.value = true;
  await library.loadSection('cloud', cursor);
  loadingMore.value = false;
}

watch(pendingDelete, (target) => {
  releaseConfirmLayer?.();
  releaseConfirmLayer = undefined;
  if (target) {
    releaseConfirmLayer = backLayers.register({ kind: 'dialog', close: cancelDelete }, `cloud-${target.id}`);
    void nextTick(() => { if (pendingDelete.value === target) tvFocusRegistry.restore('cancel-delete'); });
  }
});

watch([loggedIn, () => app.session.displayName], ([signedIn]) => {
  if (signedIn && library.sections.cloud === undefined) void library.loadSection('cloud');
}, { immediate: true });

onMounted(() => {
  void nextTick(() => tvFocusRegistry.restore(recallFocus(PAGE_KEY) ?? 'cloud-first'));
});

onBeforeUnmount(() => {
  rememberFocus(PAGE_KEY);
  releaseConfirmLayer?.();
  releaseConfirmLayer = undefined;
});
</script>

<template>
  <section class="cloud-page" data-page="library-cloud">
    <header class="page-header">
      <span class="eyebrow">LIBRARY</span>
      <h1>云盘</h1>
      <p>浏览你在云端保存的歌曲。</p>
    </header>

    <div v-if="!loggedIn" class="login-prompt">
      <p>登录后即可查看云盘</p>
      <button v-tv-focus type="button" data-focus-id="cloud-login" @click="requestLogin">立即登录</button>
    </div>

    <template v-else>
      <div v-if="section.items.length" class="cloud-rows">
        <div
          v-for="(card, index) in section.items"
          :key="card.id"
          class="cloud-row"
        >
          <span class="cloud-art" aria-hidden="true"><TvIcon name="cloud" :size="32" /></span>
          <span class="cloud-copy">
            <strong>{{ card.title }}</strong>
            <small>{{ card.subtitle }}</small>
          </span>
          <button
            v-tv-focus
            type="button"
            class="delete-btn"
            data-action="delete-cloud-item"
            :data-focus-id="`cloud-${card.id}`"
            :data-focus-up="index > 0 ? `cloud-${section.items[index - 1].id}` : undefined"
            :data-focus-down="index + 1 < section.items.length ? `cloud-${section.items[index + 1].id}` : section.status === 'error' ? 'pagination-retry' : section.nextCursor || section.status === 'loading' ? 'pagination-load-more' : undefined"
            @click="askDelete(card)"
          ><TvIcon name="trash" />删除</button>
        </div>
      </div>
      <p v-else-if="section.status === 'ready'" class="empty">云盘暂无歌曲</p>

      <p v-if="section.status === 'error'" class="empty">加载失败，请重试</p>
      <button v-if="section.status === 'error' && !section.items.length" v-tv-focus type="button" data-focus-id="cloud-retry" @click="library.loadSection('cloud')">重新加载</button>

      <ContentSkeleton v-if="section.status === 'loading' && !section.items.length" variant="songs" :count="4" label="正在读取音乐云盘" />

      <PaginationStatus
        v-if="section.items.length"
        :item-focus-ids="section.items.map(card => `cloud-${card.id}`)"
        left-focus-id="nav-library"
        :loading="section.status === 'loading'"
        :has-more="Boolean(section.nextCursor)"
        :error="section.status === 'error'"
        @load-more="loadMore"
        @retry="loadMore"
      />

      <p class="cloud-footnote">电视端不支持上传，可在手机端上传后在此浏览</p>
    </template>

    <div v-if="pendingDelete" class="confirm-backdrop">
      <section class="confirm-dialog" role="dialog" aria-modal="true" aria-label="删除确认">
        <h2>删除云盘歌曲</h2>
        <p>确定删除「{{ pendingDelete.title }}」吗？删除后无法恢复。</p>
        <div class="confirm-actions">
          <button v-tv-focus type="button" data-action="confirm-delete" data-focus-id="confirm-delete" @click="confirmDelete"><TvIcon name="trash" />删除</button>
          <button v-tv-focus type="button" data-action="cancel-delete" data-focus-id="cancel-delete" @click="cancelDelete"><TvIcon name="close" />取消</button>
        </div>
      </section>
    </div>

    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  </section>
</template>

<style scoped>
.cloud-page { padding: 4px 14px 150px; min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size: 44px; letter-spacing: -1.6px; }
.page-header p { margin: 10px 0 0; color: var(--tv-text-secondary); font-size: 17px; }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.cloud-rows { display: flex; flex-direction: column; gap: 10px; margin-top: 20px; }
.cloud-row { display: flex; align-items: center; gap: 16px; min-height: 74px; padding: 8px 14px; border: 1px solid transparent; border-radius: var(--tv-radius-card); color: var(--tv-text); background: rgba(255, 255, 255, 0.03); }
.cloud-row:focus, .cloud-row:focus-within { border-color: var(--tv-border); background: rgba(0, 113, 227, 0.18); }
.cloud-art { width: 58px; height: 58px; flex: none; display: grid; place-items: center; border-radius: 12px; font-size: 26px; background: linear-gradient(135deg, #236fa5, #75c8d2); }
.cloud-copy { min-width: 0; flex: 1; }
.cloud-copy strong, .cloud-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cloud-copy strong { font-size: 17px; }
.cloud-copy small { margin-top: 3px; color: var(--tv-text-secondary); font-size: 14px; }
.delete-btn { flex: none; height: 44px; padding: 0 20px; border: 1px solid rgba(255, 126, 126, 0.4); border-radius: 14px; color: #ffd7d7; font-size: 15px; background: rgba(120, 30, 40, 0.35); opacity: 0; transition: opacity 120ms; }
.cloud-row:focus .delete-btn, .cloud-row:focus-within .delete-btn { opacity: 1; }
.empty { padding: 30px 8px; color: var(--tv-text-secondary); font-size: 16px; }
.login-prompt { padding: 30px 8px; }
.login-prompt p { color: var(--tv-text-secondary); font-size: 16px; }
.login-prompt button { min-width: 220px; height: 52px; border: 1px solid rgba(124, 188, 255, 0.35); border-radius: 26px; color: #dceeff; background: rgba(36, 102, 164, 0.3); }
.skeleton { min-height: 200px; border-radius: var(--tv-radius-panel); background: rgba(255, 255, 255, 0.05); }
.cloud-footnote { margin-top: 22px; color: var(--tv-text-secondary); font-size: 14px; }
.confirm-backdrop { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; background: rgba(3, 7, 12, 0.66); }
.confirm-dialog { width: 520px; padding: 36px; border: 1px solid var(--tv-border); border-radius: 26px; background: rgba(20, 29, 44, 0.96); box-shadow: 0 30px 90px rgba(0, 0, 0, 0.5); }
.confirm-dialog h2 { margin: 0; font-size: 30px; }
.confirm-dialog p { margin: 14px 0 0; color: var(--tv-text-secondary); font-size: 16px; line-height: 1.6; }
.confirm-actions { display: flex; gap: 14px; margin-top: 26px; }
.confirm-actions button { min-width: 140px; height: 52px; border: 0; border-radius: 26px; color: white; font-size: 16px; background: rgba(255, 255, 255, 0.12); }
.confirm-actions button:first-child { background: rgba(214, 69, 69, 0.85); }
.notice { position: fixed; left: 50%; bottom: 40px; z-index: 20; padding: 12px 24px; border-radius: 18px; transform: translateX(-50%); color: #fff; font-size: 15px; background: rgba(20, 90, 160, 0.95); }
.delete-btn,.confirm-actions button { display:inline-flex; align-items:center; justify-content:center; gap:10px; padding:0 20px; }
</style>
