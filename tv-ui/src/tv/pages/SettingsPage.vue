<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { nativeClient } from '../native/client';
import { recallFocus, rememberFocus } from '../content/routeMemory';
import { useTransientNotice } from '../content/notice';
import { useInitialFocusRestore } from '../content/initialFocus';
import { useTvSettingsStore } from '../stores/settings';
import { useRouter } from 'vue-router';

const PAGE_KEY = 'settings';
const restoreInitialFocus = useInitialFocusRestore('nav-more');
const settings = useTvSettingsStore();
const router = useRouter();
const sleepEndsAt = ref(0);
const sleepBusy = ref(false);
async function sleepTimer(minutes?: number) {
  if (sleepBusy.value) return;
  sleepBusy.value = true;
  try {
    const result = await nativeClient.request<{ accepted: boolean; endsAt: number }>('player.sleepTimer', minutes === undefined ? {} : { minutes });
    if (!result.accepted || !Number.isFinite(result.endsAt)) throw new Error('Timer unavailable');
    sleepEndsAt.value = result.endsAt;
  } catch { if (minutes !== undefined) show('定时设置失败，请先开始播放音乐后重试'); }
  finally { sleepBusy.value = false; }
}
const { notice, show } = useTransientNotice();

const fontOptions = [
  { value: 80, label: '小' },
  { value: 100, label: '标准' },
  { value: 120, label: '大' },
  { value: 140, label: '超大' },
] as const;

async function toggle(key: 'reducedMotion' | 'compatiblePluginsEnabled'): Promise<void> {
  const ok = await settings.update(key, !settings.settings[key]);
  if (!ok) show('设置保存失败，请重试');
}

async function chooseFont(value: number): Promise<void> {
  const ok = await settings.update('lyricFontScale', value);
  if (!ok) show('设置保存失败，请重试');
}

async function chooseBackground(value: 'blur' | 'gradient'): Promise<void> {
  const ok = await settings.update('lyricBackground', value);
  if (!ok) show('设置保存失败，请重试');
}

async function chooseQuality(value: string): Promise<void> {
  if (!await settings.update('defaultQuality', value)) show('音质偏好保存失败，请重试');
}

onMounted(async () => {
  void sleepTimer();
  if (!settings.loaded) await settings.load();
  void nextTick(() => restoreInitialFocus(recallFocus(PAGE_KEY) ?? 'settings-reduced-motion'));
});

onBeforeUnmount(() => rememberFocus(PAGE_KEY));
</script>

<template>
  <section class="settings-page" data-page="settings">
    <header class="page-header">
      <span class="eyebrow">SETTINGS</span>
      <h1>设置</h1>
      <p>为电视场景调整播放与显示。</p>
    </header>

    <section class="settings-group" aria-labelledby="appearance-title">
      <h2 id="appearance-title">外观</h2>
      <div class="setting-row">
        <div><strong>减少动态效果</strong><small>关闭动态背景与多余动画</small></div>
        <button v-tv-focus type="button" data-focus-id="settings-reduced-motion" @click="toggle('reducedMotion')">{{ settings.settings.reducedMotion ? '已开启' : '已关闭' }}</button>
      </div>
    </section>

    <section class="settings-group" aria-labelledby="playback-title">
      <h2 id="playback-title">播放</h2>
      <p class="settings-help">在播放界面的「音质」中选择当前歌曲可用音质。</p>
      <div class="setting-row"><div><strong>睡眠定时</strong><small>{{ sleepEndsAt > Date.now() ? `音乐将在 ${new Date(sleepEndsAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} 暂停` : '仅暂停音乐，不关闭电视；本次播放服务内有效' }}</small></div><div class="options"><button v-for="minutes in [0, 15, 30, 60, 90]" :key="minutes" v-tv-focus type="button" :data-focus-id="`settings-sleep-${minutes}`" :aria-busy="sleepBusy" @click="sleepTimer(minutes)">{{ minutes ? `${minutes} 分` : '关闭' }}</button></div></div>
      <div class="setting-row"><div><strong>默认音质偏好</strong><small>对新播放的歌曲生效；不可用时自动回退，不改变会员权限</small></div><div class="options"><button v-for="option in [{ value: '128', label: '标准' }, { value: '320', label: '高品' }, { value: 'flac', label: '无损优先' }]" :key="option.value" v-tv-focus type="button" :data-focus-id="`settings-quality-${option.value}`" :aria-pressed="settings.settings.defaultQuality === option.value" :class="{ active: settings.settings.defaultQuality === option.value }" @click="chooseQuality(option.value)">{{ option.label }}</button></div></div>
    </section>

    <section class="settings-group" aria-labelledby="lyrics-title">
      <h2 id="lyrics-title">歌词</h2>
      <div class="setting-row">
        <div><strong>歌词字号</strong><small>调整播放页歌词大小</small></div>
        <div class="options">
          <button
            v-for="option in fontOptions"
            :key="option.value"
            v-tv-focus
            type="button"
            :data-focus-id="`settings-font-${option.value}`"
            :class="{ active: settings.settings.lyricFontScale === option.value }"
            @click="chooseFont(option.value)"
          >{{ option.label }}</button>
        </div>
      </div>
      <div class="setting-row">
        <div><strong>歌词背景</strong><small>歌词区的玻璃或渐变背景</small></div>
        <div class="options">
          <button v-tv-focus type="button" data-focus-id="settings-bg-blur" :class="{ active: settings.settings.lyricBackground === 'blur' }" @click="chooseBackground('blur')">玻璃</button>
          <button v-tv-focus type="button" data-focus-id="settings-bg-gradient" :class="{ active: settings.settings.lyricBackground === 'gradient' }" @click="chooseBackground('gradient')">渐变</button>
        </div>
      </div>
    </section>

    <section class="settings-group" aria-labelledby="plugins-title">
      <h2 id="plugins-title">兼容插件</h2>
      <div class="setting-row">
        <div><strong>启用兼容插件</strong><small>仅加载声明 Android TV 能力的本地插件</small></div>
        <button v-tv-focus type="button" data-focus-id="settings-plugins" @click="toggle('compatiblePluginsEnabled')">{{ settings.settings.compatiblePluginsEnabled ? '已启用' : '已停用' }}</button>
      </div>
      <div class="setting-row"><div><strong>管理本地扩展</strong><small>查看当前电视支持的兼容插件</small></div><button v-tv-focus type="button" data-focus-id="settings-manage-plugins" @click="router.push({ name: 'plugin', query: { sourceFocusId: 'settings-manage-plugins' } })">查看扩展</button></div>
    </section>

    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  </section>
</template>

<style scoped>
.settings-page { padding: 4px 14px 150px; min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size: 44px; letter-spacing: -1.6px; }
.page-header p { margin: 10px 0 0; color: var(--tv-text-secondary); font-size: 17px; }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.settings-group { margin-top: 26px; }
.settings-group h2 { margin: 0 0 12px; font-size: 22px; }
.setting-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; min-height: 68px; padding: 10px 18px; border-radius: var(--tv-radius-card); background: rgba(255, 255, 255, 0.03); margin-top: 8px; }
.setting-row strong { display: block; font-size: 16px; }
.setting-row small { display: block; margin-top: 3px; color: var(--tv-text-secondary); font-size: 13px; }
.setting-row > button { min-width: 130px; height: 48px; border: 1px solid var(--tv-border); border-radius: 24px; color: var(--tv-text); font-size: 15px; background: transparent; }
.options { display: flex; gap: 8px; }
.options button { min-width: 92px; height: 46px; border: 1px solid transparent; border-radius: 23px; color: var(--tv-text-secondary); font-size: 15px; background: rgba(255, 255, 255, 0.05); }
.options button.active { color: white; background: rgba(0, 113, 227, 0.4); }
.notice { position: fixed; left: 50%; bottom: 40px; z-index: 20; padding: 12px 24px; border-radius: 18px; transform: translateX(-50%); color: #fff; font-size: 15px; background: rgba(20, 90, 160, 0.95); }
.setting-row { min-height:86px; padding:16px 22px; }.setting-row strong { font-size:24px; }.setting-row small,.settings-help { font-size:21px; color:var(--tv-text-secondary); }.setting-row > button,.options button { font-size:23px; min-height:62px; }.settings-group h2 { font-size:28px; }.options button.active { background:var(--tv-accent); color:#241d22; }
</style>
