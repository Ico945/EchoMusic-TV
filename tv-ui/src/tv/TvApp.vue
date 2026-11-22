<template>
  <main
    ref="canvasElement"
    class="tv-canvas"
    :class="{ 'tv-ime-open': keyboardOpen, 'tv-no-blur': !capabilityStore.loaded || !capabilityStore.capabilities.blur, 'tv-reduced-motion': settingsStore.settings.reducedMotion }"
    data-tv-app="ready"
    :style="{ transform: `scale(${canvasScale})`, '--tv-content-bottom-safe': `${contentBottomInsetForScale(canvasScale)}px`, '--tv-lyric-scale': settingsStore.settings.lyricFontScale / 100 }"
  >
    <section class="tv-safe-area" data-tv-safe-area>
      <TvShell />
    </section>
  </main>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useTvSettingsStore } from './stores/settings';
import TvShell from './layout/TvShell.vue';
import { canvasScaleForViewport, contentBottomInsetForScale, viewportWithKeyboard } from './layout/canvasScale';
import { useTvCapabilitiesStore } from './stores/capabilities';

import { installTvMotion } from './input/motion';

const canvasElement = ref<HTMLElement | null>(null);
let releaseMotion: (() => void) | undefined;
let resumeFrame: number | undefined;
const canvasScale = ref(canvasScaleForViewport(window.innerWidth, window.innerHeight));
const keyboardOpen = ref(false);
let stableViewport = { width: window.innerWidth, height: window.innerHeight };
const capabilityStore = useTvCapabilitiesStore();
const settingsStore = useTvSettingsStore();
const updateCanvasScale = () => {
  if (document.hidden || window.innerWidth <= 0 || window.innerHeight <= 0) return;
  const editing = Boolean(document.querySelector('[data-tv-ime-active="true"]')) || keyboardOpen.value;
  const viewport = viewportWithKeyboard(window.innerWidth, window.innerHeight, stableViewport, editing);
  keyboardOpen.value = viewport.keyboard;
  if (!viewport.keyboard) stableViewport = viewport;
  canvasScale.value = canvasScaleForViewport(viewport.width, viewport.height);
};
const resumeCanvas = () => {
  updateCanvasScale();
  if (resumeFrame !== undefined) cancelAnimationFrame(resumeFrame);
  resumeFrame = requestAnimationFrame(() => { resumeFrame = undefined; updateCanvasScale(); });
};
const visibilityChanged = () => { if (!document.hidden) resumeCanvas(); };

onMounted(() => {
  if (canvasElement.value) releaseMotion = installTvMotion(canvasElement.value);
  window.addEventListener('resize', updateCanvasScale);
  window.addEventListener('echo-tv-resume', resumeCanvas);
  document.addEventListener('visibilitychange', visibilityChanged);
  void capabilityStore.load();
  if (!settingsStore.loaded) void settingsStore.load();
});
onBeforeUnmount(() => {
  window.removeEventListener('resize', updateCanvasScale);
  window.removeEventListener('echo-tv-resume', resumeCanvas);
  document.removeEventListener('visibilitychange', visibilityChanged);
  if (resumeFrame !== undefined) cancelAnimationFrame(resumeFrame);
  releaseMotion?.();
});
</script>
