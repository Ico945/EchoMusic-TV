import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import { createTvRouter } from '../router';
import SettingsPage from './SettingsPage.vue';
import RecognizePage from './RecognizePage.vue';

const fullSettings = {
  reducedMotion: false,
  defaultQuality: '128',
  lyricFontScale: 100,
  lyricBackground: 'blur',
  cacheLimitMb: 1024,
  compatiblePluginsEnabled: true,
};

function installClient(handlers: Record<string, unknown> = {}) {
  const client: NativeClientLike = {
    request: vi.fn((method: string, payload: unknown) => {
      if (method === 'settings.get') return Promise.resolve({ settings: fullSettings, cacheBytes: 0 });
      if (method === 'settings.update') {
        const record = payload as Record<string, unknown>;
        return Promise.resolve({ settings: { ...fullSettings, [record.key as string]: record.value }, cacheBytes: 0 });
      }
      if (method === 'app.clearCache') return Promise.resolve({ cacheBytes: 0 });
      if (method === 'capability.get') return Promise.resolve({ microphone: 'available', voiceInput: false, blur: true });
      if (method === 'recognition.start') return Promise.resolve({ state: 'recording', matches: [], message: '' });
      if (method === 'recognition.cancel') return Promise.resolve({ state: 'idle', matches: [], message: '' });
      return Promise.resolve(handlers[method] ?? {});
    }) as NativeClientLike['request'],
    on: vi.fn(() => () => undefined),
  };
  installNativeClientForTest(client);
  return client;
}

function mountPage(component: unknown) {
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createTvRouter();
  return mount(component as never, {
    global: {
      plugins: [pinia, router],
      directives: { tvFocus: {} },
      provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: new BackLayerRegistry(() => undefined) },
    },
  });
}

describe('SettingsPage', () => {
  it('renders the approved TV settings sections', async () => {
    installClient();
    const wrapper = mountPage(SettingsPage);
    await flushPromises();
    for (const section of ['外观', '播放', '歌词', '兼容插件']) {
      expect(wrapper.text()).toContain(section);
    }
    expect(wrapper.text()).not.toContain('桌面歌词');
    expect(wrapper.text()).not.toContain('快捷键');
  });

  it('persists lyric size and native default quality while hiding unimplemented cache controls', async () => {
    const client = installClient();
    const wrapper = mountPage(SettingsPage);
    await flushPromises();
    await wrapper.get('[data-focus-id="settings-font-140"]').trigger('click');
    await flushPromises();
    const updateCalls = (client.request as ReturnType<typeof vi.fn>).mock.calls.filter((call) => call[0] === 'settings.update');
    expect(wrapper.find('[data-focus-id="settings-clear-cache"]').exists()).toBe(false);
    await wrapper.get('[data-focus-id="settings-quality-320"]').trigger('click');
    await flushPromises();
    expect(client.request).toHaveBeenCalledWith('settings.update', { key: 'defaultQuality', value: '320' });
    expect(updateCalls.length).toBeGreaterThan(0);
    expect(updateCalls[0]?.[1]).toMatchObject({ key: 'lyricFontScale', value: 140 });
  });
});

describe('RecognizePage', () => {
  it('shows search as the alternative when recognition is unavailable', async () => {
    installClient({ });
    const client2: NativeClientLike = {
      request: vi.fn((method: string) => {
        if (method === 'capability.get') return Promise.resolve({ microphone: 'unavailable', voiceInput: false, blur: true });
        return Promise.resolve({});
      }) as NativeClientLike['request'],
      on: vi.fn(() => () => undefined),
    };
    installNativeClientForTest(client2);
    const wrapper = mountPage(RecognizePage);
    await flushPromises();
    expect(wrapper.get('[data-action="open-search"]')).toBeTruthy();
    expect(wrapper.findAll('[data-action="start-recognition"]')).toHaveLength(0);
  });

  it('starts recognition when the microphone is available', async () => {
    const client = installClient();
    const wrapper = mountPage(RecognizePage);
    await flushPromises();
    await wrapper.get('[data-action="start-recognition"]').trigger('click');
    await flushPromises();
    expect((client.request as ReturnType<typeof vi.fn>).mock.calls.some((call) => call[0] === 'recognition.start')).toBe(true);
  });
});
