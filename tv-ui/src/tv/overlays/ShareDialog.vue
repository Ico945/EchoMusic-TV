<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useTransientNotice } from '../content/notice';
import { tvFocusRegistry } from '../focus/directive';
import { useBackLayerRegistry } from '../input/backCoordinator';
import { useTvShareStore, type TvShareResult } from '../stores/share';

const props = defineProps<{
  kind: string;
  id: string;
  title?: string;
  sourceFocusId?: string;
}>();

const emit = defineEmits<{ close: [] }>();

const share = useTvShareStore();
const backLayers = useBackLayerRegistry();
const { notice, show } = useTransientNotice();

const loading = ref(true);
const error = ref(false);
const result = ref<TvShareResult | null>(null);

let releaseBackLayer: (() => void) | undefined;
let dialogActive = false;
let loadGeneration = 0;

function close(): void {
  emit('close');
  releaseBackLayer?.();
  releaseBackLayer = undefined;
}

async function load(): Promise<void> {
  const generation = ++loadGeneration;
  loading.value = true;
  error.value = false;
  result.value = null;
  const next = await share.create(props.kind, props.id);
  if (!dialogActive || generation !== loadGeneration) return;
  loading.value = false;
  if (!next) {
    error.value = true;
  } else {
    result.value = next;
  }
  await nextTick();
  if (!dialogActive || generation !== loadGeneration) return;
  tvFocusRegistry.restore(next ? 'share-close' : 'share-retry');
}

async function copyLink(): Promise<void> {
  if (!result.value) return;
  try {
    await navigator.clipboard.writeText(result.value.link);
    show('链接已复制');
  } catch {
    show('复制失败，请手动复制');
  }
}

onMounted(() => {
  dialogActive = true;
  releaseBackLayer = backLayers.register({ kind: 'dialog', close }, props.sourceFocusId);
  void load();
});

onBeforeUnmount(() => {
  dialogActive = false;
  loadGeneration += 1;
  releaseBackLayer?.();
  releaseBackLayer = undefined;
});
</script>

<template>
  <div class="share-backdrop" data-share-dialog>
    <section class="share-dialog" role="dialog" aria-modal="true" aria-label="分享" :data-share-state="loading ? 'loading' : error ? 'error' : 'ready'">
      <div class="share-head">
        <span>分享</span>
        <h2>{{ title ?? '分享内容' }}</h2>
      </div>

      <div v-if="loading" class="share-body">正在生成分享…</div>
      <div v-else-if="error" class="share-body">
        <p>分享生成失败</p>
        <button v-tv-focus type="button" data-focus-id="share-retry"
          data-focus-up="share-retry" data-focus-down="share-retry" data-focus-left="share-retry" data-focus-right="share-retry"
          @click="load"><TvIcon name="refresh" />重试</button>
      </div>
      <div v-else-if="result" class="share-body">
        <img class="qr" :src="result.qrImageDataUrl" alt="分享二维码" />
        <p class="share-url">{{ result.link }}</p>
        <div class="share-actions">
          <button
            v-if="result.clipboardAvailable"
            v-tv-focus
            type="button"
            data-action="copy-share-link"
            data-focus-id="share-copy"
            data-focus-up="share-copy"
            data-focus-down="share-copy"
            data-focus-left="share-copy"
            data-focus-right="share-close"
            @click="copyLink"
          ><TvIcon name="copy" />复制链接</button>
          <button v-tv-focus type="button" data-action="close-share-dialog" data-focus-id="share-close"
            data-focus-up="share-close" data-focus-down="share-close" data-focus-right="share-close"
            :data-focus-left="result.clipboardAvailable ? 'share-copy' : 'share-close'"
            @click="close"><TvIcon name="close" />关闭</button>
        </div>
      </div>

      <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    </section>
  </div>
</template>

<style scoped>
.share-backdrop { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; background: rgba(3, 7, 12, 0.72); }
.share-dialog { width: 480px; padding: 36px; border: 1px solid var(--tv-border); border-radius: 26px; background: rgba(20, 29, 44, 0.96); box-shadow: 0 30px 90px rgba(0, 0, 0, 0.5); }
.share-head span { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.share-head h2 { margin: 8px 0 0; font-size: 26px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.share-body { margin-top: 22px; display: flex; flex-direction: column; align-items: center; gap: 16px; }
.share-body .qr { width: 220px; height: 220px; border-radius: 16px; background: white; }
.share-url { margin: 0; color: var(--tv-text-secondary); font-size: 13px; word-break: break-all; text-align: center; }
.share-body p { color: var(--tv-text-secondary); }
.share-body button { min-width: 200px; height: 52px; border: 1px solid rgba(124, 188, 255, 0.35); border-radius: 26px; color: #dceeff; background: rgba(36, 102, 164, 0.3); }
.share-actions { display: flex; gap: 14px; margin-top: 6px; }
.share-actions button { min-width: 150px; }
.notice { position: absolute; bottom: 24px; left: 50%; transform: translateX(-50%); padding: 10px 20px; border-radius: 14px; color: #fff; font-size: 14px; background: rgba(20, 90, 160, 0.95); }
.share-body button { display:inline-flex; align-items:center; justify-content:center; gap:10px; padding:0 20px; }
</style>
