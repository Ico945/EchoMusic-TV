<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import TvOverlayTransition from '../components/TvOverlayTransition.vue';
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useBackLayerRegistry } from '../input/backCoordinator';
import { tvFocusRegistry } from '../focus/directive';
import { useTvAuthStore } from '../stores/auth';
import { useTvAppStore } from '../stores/app';

const props = defineProps<{ open: boolean; sourceFocusId?: string }>();
const emit = defineEmits<{ close: []; confirmed: [] }>();

const auth = useTvAuthStore();
const app = useTvAppStore();
const backLayers = useBackLayerRegistry();
const busy = ref(false);
const active = ref(false);
let dialogGeneration = 0;
let releaseBackLayer: (() => void) | undefined;

function finish(emitClose = true): void {
  if (!active.value) return;
  active.value = false;
  dialogGeneration += 1;
  busy.value = false;
  releaseBackLayer?.();
  releaseBackLayer = undefined;
  if (emitClose) emit('close');
}

function cancel(emitClose = true): void {
  if (!active.value) return;
  // Dispatch cancellation before closing, but never make Back await the bridge.
  if (auth.state !== 'loggedIn') void auth.cancelQr().catch(() => undefined);
  finish(emitClose);
}

async function restoreDialogFocus(generation: number): Promise<void> {
  await nextTick();
  if (!active.value || !props.open || generation !== dialogGeneration) return;
  const focused = document.activeElement as HTMLElement | null;
  if (focused?.closest('.login-dialog') && !focused.matches(':disabled')) return;
  const target = (auth.state === 'qrPending' || auth.state === 'scanned') && auth.qrImageDataUrl
    ? 'login-qr' : !busy.value && (auth.state === 'expired' || auth.state === 'loggedOut')
      ? 'login-regenerate' : 'login-cancel';
  tvFocusRegistry.restore(target);
}

async function begin(): Promise<void> {
  if (!active.value || busy.value) return;
  const generation = dialogGeneration;
  busy.value = true;
  try {
    await auth.beginQr();
  } catch {
    // Current-generation errors are rendered from the auth store.
  } finally {
    if (active.value && generation === dialogGeneration) {
      busy.value = false;
      void restoreDialogFocus(generation);
    }
  }
}

async function regenerate(): Promise<void> {
  await begin();
}

watch(
  () => auth.state,
  async (state) => {
    if (!active.value || !props.open) return;
    const generation = dialogGeneration;
    if (state === 'loggedIn') {
      await app.loadSnapshot();
      if (!active.value || generation !== dialogGeneration) return;
      finish();
      emit('confirmed');
    } else {
      void restoreDialogFocus(generation);
    }
  },
);

watch(
  () => props.open,
  (open) => {
    if (!open) { cancel(false); return; }
    active.value = true;
    dialogGeneration += 1;
    releaseBackLayer = backLayers.register({ kind: 'dialog', close: () => cancel() }, props.sourceFocusId);
    void begin();
  },
  { immediate: true },
);

onBeforeUnmount(() => cancel(false));
</script>

<template>
  <TvOverlayTransition>
  <div v-if="open && active" class="login-backdrop">
    <section class="login-dialog" role="dialog" aria-modal="true" aria-labelledby="login-title">
      <span class="eyebrow">KU·TV 概念版</span>
      <h2 id="login-title">登录酷狗音乐</h2>
      <p class="hint">请使用手机上的<strong>酷狗概念版 App</strong>扫码登录</p>

      <template v-if="busy && !auth.qrImageDataUrl">
        <div class="qr-placeholder" aria-label="二维码加载中">加载中…</div>
        <p class="status">正在生成二维码…</p>
      </template>

      <template v-else-if="auth.state === 'qrPending'">
        <img
          v-tv-focus
          v-if="auth.qrImageDataUrl"
          :src="auth.qrImageDataUrl"
          alt="登录二维码"
          class="qr"
          data-focus-id="login-qr"
          data-focus-down="login-cancel"
          tabindex="0"
        />
        <div v-else class="qr-placeholder" aria-label="二维码加载中">加载中…</div>
        <p class="status">等待扫码…</p>
      </template>

      <template v-else-if="auth.state === 'scanned'">
        <img v-if="auth.qrImageDataUrl" v-tv-focus :src="auth.qrImageDataUrl" alt="登录二维码" class="qr dim" data-focus-id="login-qr" data-focus-down="login-cancel" tabindex="0" />
        <p class="status">已扫码，请在手机上确认登录</p>
      </template>

      <template v-else-if="auth.state === 'expired'">
        <div class="qr-placeholder expired" aria-label="二维码已过期">二维码已过期</div>
        <button v-tv-focus type="button" class="primary" data-action="regenerate-qr" data-focus-id="login-regenerate" :disabled="busy" @click="regenerate"><TvIcon name="refresh" />重新生成二维码</button>
      </template>

      <template v-else>
        <p class="status">二维码生成失败，请重试</p>
        <small v-if="auth.lastError" class="error-detail">{{ auth.lastError.code }} · {{ auth.lastError.message }}</small>
        <button v-tv-focus type="button" class="primary" data-action="regenerate-qr" data-focus-id="login-regenerate" :disabled="busy" @click="regenerate"><TvIcon name="refresh" />重试</button>
      </template>

      <div class="actions">
        <button v-tv-focus type="button" data-focus-id="login-cancel" @click="cancel()"><TvIcon name="close" />取消</button>
      </div>
    </section>
  </div>
  </TvOverlayTransition>
</template>

<style scoped>
.login-backdrop { position: fixed; inset: 0; z-index: 40; display: grid; place-items: center; background: rgba(3, 7, 12, 0.82); }
.login-dialog { width: 520px; padding: 42px; border: 1px solid var(--tv-border); border-radius: 30px; text-align: center; background: rgba(20, 29, 44, 0.96); box-shadow: 0 35px 100px rgba(0, 0, 0, 0.5); }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 2px; font-weight: 700; }
.login-dialog h2 { margin: 10px 0 8px; font-size: 34px; }
.hint { margin: 0 0 20px; color: var(--tv-text-secondary); font-size: 16px; }
.hint strong { color: #dceeff; }
.qr { width: 300px; height: 300px; margin: 0 auto; padding: 10px; border-radius: 18px; background: white; outline: none; }
.qr.dim { opacity: 0.55; }
.qr-placeholder { width: 300px; height: 300px; margin: 0 auto; display: grid; place-items: center; border-radius: 18px; color: var(--tv-text-secondary); font-size: 18px; background: rgba(255, 255, 255, 0.06); }
.qr-placeholder.expired { color: #ffd76e; }
.status { margin: 16px 0 8px; color: #d8e6f7; font-size: 16px; }
.error-detail { display: block; margin: 4px 0 8px; color: var(--tv-text-secondary); font-size: 13px; }
.login-dialog .primary { min-width: 220px; height: 52px; margin-top: 14px; border: 0; border-radius: 26px; font-size: 16px; font-weight: 700; color: #07111d; background: white; }
.actions { margin-top: 20px; }
.actions button { min-width: 150px; height: 50px; border: 1px solid var(--tv-border); border-radius: 25px; color: var(--tv-text); font-size: 16px; background: transparent; }
.login-dialog button { display:inline-flex; align-items:center; justify-content:center; gap:10px; padding:0 20px; }
</style>
