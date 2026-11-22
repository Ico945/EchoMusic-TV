<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { nativeClient } from '../native/client';
import { recallFocus, rememberFocus } from '../content/routeMemory';
import { useTransientNotice } from '../content/notice';
import { tvFocusRegistry } from '../focus/directive';
import { isNativeBridgeAvailable } from '../native/transport';

const PAGE_KEY = 'plugin';
const { notice, show } = useTransientNotice();

type TvPluginInfo = {
  id: string;
  version: string;
  capabilities: string[];
  bridgeScopes: string[];
};

const plugins = ref<TvPluginInfo[]>([]);
const status = ref<'loading' | 'ready' | 'error'>('loading');
const disabledReason = ref('');
let alive = true;

function parsePlugins(value: unknown): TvPluginInfo[] {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('plugin list is invalid');
  const record = value as Record<string, unknown>;
  const items = record.plugins ?? record.items;
  if (!Array.isArray(items)) throw new Error('plugin items are invalid');
  return items.map((item) => {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) throw new Error('plugin item is invalid');
    const plugin = item as Record<string, unknown>;
    if (
      typeof plugin.id !== 'string' ||
      typeof plugin.version !== 'string' ||
      !Array.isArray(plugin.capabilities) ||
      !Array.isArray(plugin.bridgeScopes)
    ) {
      throw new Error('plugin item is invalid');
    }
    return {
      id: plugin.id,
      version: plugin.version,
      capabilities: plugin.capabilities.filter((capability): capability is string => typeof capability === 'string'),
      bridgeScopes: plugin.bridgeScopes.filter((scope): scope is string => typeof scope === 'string'),
    };
  });
}

async function load(): Promise<void> {
  if (!alive) return;
  const origin = (document.activeElement as HTMLElement | null)?.dataset.focusId;
  status.value = 'loading';
  if (!isNativeBridgeAvailable()) {
    plugins.value = [];
    status.value = 'ready';
    return;
  }
  try {
    const payload = await nativeClient.request<unknown>('plugins.list', {});
    if (!alive) return;
    plugins.value = parsePlugins(payload);
    const reason = (payload as Record<string, unknown>).disabledReason;
    disabledReason.value = typeof reason === 'string' ? '当前设备暂不支持运行扩展' : '';
    status.value = 'ready';
  } catch {
    if (alive) status.value = 'error';
  }
  await nextTick();
  const current = (document.activeElement as HTMLElement | null)?.dataset.focusId;
  if (alive && (!origin || origin === 'plugin-retry' || origin === 'nav-more') && (!current || current === 'plugin-retry')) {
    tvFocusRegistry.restore(status.value === 'ready' && plugins.value.length ? `plugin-${plugins.value[0].id}` : 'plugin-retry');
  }
}

async function invoke(pluginId: string): Promise<void> {
  try {
    await nativeClient.request<unknown>('plugins.invoke', { id: pluginId, action: 'open' });
    show('已打开插件');
  } catch {
    show('插件暂不可用');
  }
}

onMounted(() => {
  void load();
  void nextTick(() => tvFocusRegistry.restore(recallFocus(PAGE_KEY) ?? 'plugin-retry'));
});

onBeforeUnmount(() => { alive = false; rememberFocus(PAGE_KEY); });
</script>

<template>
  <section class="plugin-page" data-page="plugin" data-default-focus-id="plugin-retry">
    <header class="page-header">
      <span class="eyebrow">PLUGINS</span>
      <h1>兼容插件</h1>
      <p>在高级设置中管理此设备支持的本地扩展。</p>
    </header>

    <div v-if="status === 'ready' && plugins.length" class="plugin-list">
      <div v-for="plugin in plugins" :key="plugin.id" v-tv-focus class="plugin-card" :data-focus-id="`plugin-${plugin.id}`" tabindex="0" @keydown.enter.prevent.stop="invoke(plugin.id)">
        <strong>{{ plugin.id }}</strong>
        <small>v{{ plugin.version }} · {{ plugin.capabilities.join(', ') }} · 桥接范围 {{ plugin.bridgeScopes.join(', ') }}</small>
      </div>
    </div>
    <p v-else-if="status === 'ready'" class="empty">{{ disabledReason || '暂无兼容插件' }}</p>
    <button v-tv-focus type="button" data-focus-id="plugin-retry" :aria-busy="status === 'loading'" @click="status !== 'loading' && load()">{{ status === 'loading' ? '正在检查扩展…' : status === 'error' ? '扩展列表加载失败，确认重试' : '重新检查扩展' }}</button>

    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  </section>
</template>

<style scoped>
.plugin-page { padding: 4px 14px 150px; min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size: 44px; letter-spacing: -1.6px; }
.page-header p { margin: 10px 0 0; color: var(--tv-text-secondary); font-size: 17px; }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.plugin-list { display: flex; flex-direction: column; gap: 10px; margin-top: 22px; }
.plugin-card { padding: 16px 20px; border: 1px solid transparent; border-radius: var(--tv-radius-card); background: rgba(255, 255, 255, 0.03); outline: none; }
.plugin-card:focus { border-color: var(--tv-border); background: rgba(0, 113, 227, 0.18); }
.plugin-card strong { display: block; font-size: 17px; }
.plugin-card small { display: block; margin-top: 4px; color: var(--tv-text-secondary); font-size: 14px; }
.empty { padding: 30px 8px; color: var(--tv-text-secondary); font-size: 16px; }
.plugin-page > button { min-width: 260px; height: 52px; margin-top: 20px; border: 1px solid rgba(124, 188, 255, 0.35); border-radius: 26px; color: #dceeff; background: rgba(36, 102, 164, 0.3); }
.skeleton { min-height: 200px; border-radius: var(--tv-radius-panel); background: rgba(255, 255, 255, 0.05); }
.notice { position: fixed; left: 50%; bottom: 40px; z-index: 20; padding: 12px 24px; border-radius: 18px; transform: translateX(-50%); color: #fff; font-size: 15px; background: rgba(20, 90, 160, 0.95); }
</style>
