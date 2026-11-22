<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import TvIcon from '../components/TvIcon.vue';
import { tvFocusRegistry } from '../focus/directive';
import { scrollReadingPanel } from '../content/reading';
import { nativeClient } from '../native/client';
const version = import.meta.env.VITE_ECHO_TV_VERSION || '开发预览';
const licenseText = ref('');
const licenseTitle = ref('');
let generation = 0;
async function readLicense(kind: 'gpl' | 'notices') {
  const token = ++generation;
  licenseTitle.value = kind === 'gpl' ? 'GNU GPL v3' : '第三方声明';
  licenseText.value = '正在读取…';
  await nextTick(); tvFocusRegistry.restore('about-license-text');
  try {
    // Keep connect-src 'none': read only the two packaged native assets.
    const result = await nativeClient.request<{ text: string }>('app.readLicense', { kind });
    if (typeof result.text !== 'string' || !result.text.trim()) throw new Error('License unavailable');
    if (token === generation) licenseText.value = result.text;
  } catch { if (token === generation) licenseText.value = '许可证读取失败，请返回上方按钮重试。'; }
}
onMounted(() => { void nextTick(() => tvFocusRegistry.restore('about-license-gpl')); });
onBeforeUnmount(() => { generation++; });
</script>

<template>
  <section class="about-page" data-page="about" data-default-focus-id="about-license-gpl">
    <header class="page-header">
      <span class="eyebrow">ABOUT</span>
      <h1>关于</h1>
    </header>

    <div class="about-card">
      <span class="logo" aria-hidden="true"><TvIcon name="music" :size="52" /></span>
      <h2>EchoMusic TV</h2>
      <p>版本 {{ version }}</p>
      <p>客厅场景的 EchoMusic，基于 Android TV 与酷狗概念版能力。</p>
    </div>

    <section class="licenses" aria-labelledby="licenses-title">
      <h2 id="licenses-title">开源许可证</h2>
      <p>EchoMusic 采用 GPL-3.0 许可证。确认打开，方向键阅读完整文本。</p>
      <div class="license-actions"><button v-tv-focus type="button" data-focus-id="about-license-gpl" data-focus-right="about-license-notices" :data-focus-down="licenseText ? 'about-license-text' : 'about-license-gpl'" @click="readLicense('gpl')">GNU GPL v3</button><button v-tv-focus type="button" data-focus-id="about-license-notices" data-focus-left="about-license-gpl" :data-focus-down="licenseText ? 'about-license-text' : 'about-license-notices'" @click="readLicense('notices')">第三方声明</button></div>
      <div v-if="licenseText" v-tv-focus tabindex="0" class="license-reader" data-focus-id="about-license-text" data-focus-up="about-license-gpl" :aria-label="`${licenseTitle}，上下键滚动阅读`" @keydown="scrollReadingPanel"><h3>{{ licenseTitle }}</h3><pre>{{ licenseText }}</pre></div>
    </section>
  </section>
</template>

<style scoped>
.about-page { padding: 4px 14px 150px; min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size: 44px; letter-spacing: -1.6px; }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.about-card { display: grid; place-items: center; gap: 10px; margin-top: 26px; padding: 40px; border-radius: var(--tv-radius-panel); background: rgba(255, 255, 255, 0.04); }
.logo { width: 90px; height: 90px; display: grid; place-items: center; border-radius: 22px; font-size: 40px; font-weight: 800; color: white; background: linear-gradient(145deg, #39a0ff, #0066d6); box-shadow: 0 20px 50px rgba(0, 113, 227, 0.38); }
.about-card h2 { margin: 8px 0 0; font-size: 30px; }
.about-card p { margin: 0; color: var(--tv-text-secondary); font-size: 16px; }
.licenses { margin-top: 26px; }
.licenses h2 { margin: 0 0 10px; font-size: 22px; }
.licenses p { margin: 0; color: var(--tv-text-secondary); font-size: 15px; line-height: 1.7; }
</style>

<style scoped>
.logo { background:linear-gradient(145deg,#ad637b,#523444); box-shadow:none; }.eyebrow { color:var(--tv-accent); font-size:18px; }
.about-card p,.licenses p { font-size:23px; }.about-card { padding:26px; }
.license-actions { display:flex; gap:18px; margin-top:18px; }
.license-actions button { min-height:62px; padding:12px 24px; border:0; border-radius:16px; background:#ffffff12; color:var(--tv-text); font-size:23px; }
.license-reader { height:400px; overflow:auto; margin-top:20px; padding:24px; border-radius:18px; background:#ffffff08; --tv-focus-scale:1; }
.license-reader pre { white-space:pre-wrap; overflow-wrap:anywhere; font-family:inherit; font-size:23px; line-height:1.55; }
</style>
