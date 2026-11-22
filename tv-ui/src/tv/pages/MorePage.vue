<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { nextTick, onBeforeUnmount, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { recallFocus, rememberFocus } from '../content/routeMemory';
import { useInitialFocusRestore } from '../content/initialFocus';

const PAGE_KEY = 'more';
const router = useRouter();
const restoreInitialFocus = useInitialFocusRestore('nav-more');

const entries = [
  { id: 'settings', label: '设置', desc: '播放偏好、显示与高级设置', route: 'settings' },
  { id: 'recognize', label: '听歌识曲', desc: '用声音找到这首歌', route: 'recognize' },
  { id: 'share', label: '分享链接', desc: '打开来自手机的音乐链接', route: 'share-resolve' },
  { id: 'about', label: '关于', desc: '版本与许可证', route: 'about' },
] as const;

function open(route: string): void {
  void router.push({ name: route, query: { sourceFocusId: (document.activeElement as HTMLElement | null)?.dataset.focusId } });
}

onMounted(() => {
  void nextTick(() => restoreInitialFocus(recallFocus(PAGE_KEY) ?? 'more-settings'));
});

onBeforeUnmount(() => rememberFocus(PAGE_KEY));
</script>

<template>
  <section class="more-page" data-page="more">
    <header class="page-header">
      <span class="eyebrow">MORE</span>
      <h1>更多</h1>
      <p>设置、工具与关于信息。</p>
    </header>

    <div class="entry-grid">
      <button
        v-for="(entry, index) in entries"
        :key="entry.id"
        v-tv-focus
        type="button"
        class="entry-card"
        :data-focus-id="`more-${entry.id}`"
        :data-focus-group="`more-${index}`"
        :data-focus-left="index % 2 === 0 ? 'nav-more' : `more-${entries[index - 1].id}`"
        :data-focus-right="`more-${entries[index % 2 === 0 ? index + 1 : index].id}`"
        :data-focus-up="`more-${entries[index < 2 ? index : index - 2].id}`"
        :data-focus-down="`more-${entries[index < 2 ? index + 2 : index].id}`"
        @click="open(entry.route)"
      >
        <span class="entry-glyph" aria-hidden="true"><TvIcon :name="{ settings: 'settings', recognize: 'mic', share: 'share', about: 'info' }[entry.id]" :size="34" /></span>
        <strong>{{ entry.label }}</strong>
        <small>{{ entry.desc }}</small>
      </button>
    </div>
  </section>
</template>

<style scoped>
.more-page { padding:12px 22px var(--tv-content-bottom-safe); min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size:54px; letter-spacing: -1.6px; }
.page-header p { margin: 10px 0 0; color: var(--tv-text-secondary); font-size:22px; }
.eyebrow { color:var(--tv-accent); font-size:16px; letter-spacing: 1.8px; font-weight: 700; }
.entry-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; margin-top: 26px; }
.entry-card { display: flex; flex-direction: column; align-items: flex-start; gap:14px; min-height:230px; padding:30px 32px; border: 1px solid transparent; border-radius: var(--tv-radius-card); color: var(--tv-text); text-align: left; background: rgba(255, 255, 255, 0.04); }
.entry-card:focus { border-color: var(--tv-border); background:var(--tv-card-focus); }
.entry-glyph { width:68px; height:68px; display: grid; place-items: center; border-radius: 14px; font-size: 24px; color:var(--tv-accent); background:#ffffff0d; }
.entry-card strong { font-size:28px; }
.entry-card small { color: var(--tv-text-secondary); font-size:22px; }
</style>
