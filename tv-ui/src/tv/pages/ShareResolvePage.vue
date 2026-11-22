<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { recallFocus, rememberFocus } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import { useBackLayerRegistry } from '../input/backCoordinator';
import { nativeClient } from '../native/client';
import { useTvShareStore } from '../stores/share';

const PAGE_KEY = 'share-resolve';
const share = useTvShareStore();
const router = useRouter();
const backLayers = useBackLayerRegistry();

const urlInput = ref('');
const inputElement = ref<HTMLInputElement | null>(null);
const inputFocused = ref(false);
const loading = ref(false);
const error = ref('');

const SHARE_KIND_ROUTES: Record<string, string> = {
  song: 'song-detail',
  playlist: 'playlist-detail',
  album: 'album-detail',
};

let releaseImeLayer: (() => void) | undefined;
let restoreFocusAfterImeHide = false;

function openIme(): void {
  if (inputFocused.value) return;
  inputFocused.value = true;
  releaseImeLayer?.();
  releaseImeLayer = backLayers.register({ kind: 'ime', close: blurInput });
  void nativeClient.request('app.showIme', {}).catch(() => undefined);
}

function onInputEnter(): void {
  if (!inputFocused.value) {
    openIme();
    return;
  }
  void resolveUrl();
  blurInput();
}

function blurInput(): void {
  restoreFocusAfterImeHide = true;
  inputFocused.value = false;
  releaseImeLayer?.();
  releaseImeLayer = undefined;
  inputElement.value?.blur();
  const restoreSubmit = () => {
    if (!inputElement.value?.isConnected) return;
    inputElement.value.blur();
    tvFocusRegistry.restore('share-resolve-submit');
  };
  void nextTick(restoreSubmit);
  void nativeClient.request('app.hideIme', {})
    .catch(() => undefined)
    .finally(() => void nextTick(restoreSubmit));
}

function onNativeImeHidden(): void {
  if (!restoreFocusAfterImeHide) return;
  restoreFocusAfterImeHide = false;
  if (!inputElement.value?.isConnected) return;
  inputElement.value.blur();
  tvFocusRegistry.restore('share-resolve-submit');
}

async function resolveUrl(): Promise<void> {
  const value = urlInput.value.trim();
  if (!value) {
    error.value = '请输入分享链接';
    return;
  }
  loading.value = true;
  error.value = '';
  const target = await share.resolve(value);
  loading.value = false;
  if (!target) {
    error.value = '链接无效或已失效，请重试';
    return;
  }
  const routeName = SHARE_KIND_ROUTES[target.kind];
  if (!routeName) {
    error.value = '暂不支持该类型内容';
    return;
  }
  void router.push({ name: routeName, params: { id: target.id } });
}

onMounted(() => {
  window.addEventListener('echo-tv-ime-hidden', onNativeImeHidden);
  void nextTick(() => tvFocusRegistry.restore(recallFocus(PAGE_KEY) ?? 'share-resolve-input'));
});

onBeforeUnmount(() => {
  window.removeEventListener('echo-tv-ime-hidden', onNativeImeHidden);
  restoreFocusAfterImeHide = false;
  rememberFocus(PAGE_KEY);
  releaseImeLayer?.();
  releaseImeLayer = undefined;
});
</script>

<template>
  <section class="share-resolve-page" data-page="share-resolve">
    <header class="page-header">
      <span class="eyebrow">SHARE</span>
      <h1>打开分享</h1>
      <p>粘贴好友分享的链接，即可跳转到对应内容。</p>
    </header>

    <div class="resolve-bar">
      <input
        ref="inputElement"
        v-model="urlInput"
        v-tv-focus
        type="text"
        inputmode="url"
        autocomplete="off"
        autocapitalize="off"
        spellcheck="false"
        enterkeyhint="go"
        placeholder="粘贴分享链接…"
        data-focus-id="share-resolve-input"
        :data-tv-ime-active="String(inputFocused)"
        @click="openIme"
        @keydown.enter.prevent.stop="onInputEnter"
      />
      <button v-tv-focus type="button" data-focus-id="share-resolve-submit" :disabled="loading" @click="resolveUrl">
        {{ loading ? '解析中…' : '打开' }}
      </button>
    </div>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.share-resolve-page { padding: 4px 14px 150px; min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size: 44px; letter-spacing: -1.6px; }
.page-header p { margin: 10px 0 0; color: var(--tv-text-secondary); font-size: 17px; }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.resolve-bar { display: flex; gap: 14px; margin-top: 24px; }
.resolve-bar input { flex: 1; height: 60px; padding: 0 24px; border: 1px solid var(--tv-border); border-radius: 30px; color: var(--tv-text); font-size: 19px; background: rgba(255, 255, 255, 0.06); outline: none; }
.resolve-bar input:focus { border-color: var(--tv-blue); background: rgba(0, 113, 227, 0.14); }
.resolve-bar button { min-width: 130px; height: 60px; border: 0; border-radius: 30px; font-size: 17px; font-weight: 700; color: #07111d; background: white; }
.resolve-bar button:disabled { opacity: 0.6; }
.error { margin-top: 16px; padding: 12px 16px; border-radius: 12px; color: #ffd7d7; font-size: 15px; background: rgba(120, 30, 40, 0.4); }
</style>
