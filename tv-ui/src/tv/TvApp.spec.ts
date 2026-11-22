import { createPinia } from 'pinia';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createTvRouter } from './router';
import { useTvCapabilitiesStore } from './stores/capabilities';
import TvApp from './TvApp.vue';
import { installNativeClientForTest } from './native/client';

enableAutoUnmount(afterEach);
afterEach(() => vi.unstubAllGlobals());

describe('TvApp', () => {
  const request = vi.fn();
  beforeEach(() => {
    request.mockReset().mockRejectedValue(new Error('Native unavailable'));
    installNativeClientForTest({ request, on: () => () => undefined });
  });
  const mountVisualApp = () => mount(TvApp, { global: { plugins: [createPinia()], stubs: { TvShell: true } } });

  it('keeps blur disabled while capability discovery is pending, even before the bridge attaches', async () => {
    let resolve!: (value: unknown) => void;
    request.mockImplementation(() => new Promise((done) => { resolve = done; }));
    const wrapper = mountVisualApp();
    expect(wrapper.classes()).toContain('tv-no-blur');
    expect(request).toHaveBeenCalledWith('capability.get', {});
    resolve({ microphone: 'unavailable', voiceInput: false, blur: true });
    await flushPromises();
    expect(wrapper.classes()).not.toContain('tv-no-blur');
  });

  it('keeps the safe material after capability request rejection', async () => {
    const wrapper = mountVisualApp();
    await flushPromises();
    expect(request).toHaveBeenCalledWith('capability.get', {});
    expect(wrapper.classes()).toContain('tv-no-blur');
  });

  it.each([null, { microphone: 'available', voiceInput: false, blur: 'yes' }])(
    'does not enable blur from malformed capabilities %j', async (payload) => {
      request.mockResolvedValue(payload);
      const wrapper = mountVisualApp();
      await flushPromises();
      expect(wrapper.classes()).toContain('tv-no-blur');
    },
  );

  it('uses the opaque material when native capabilities confirm no blur support', async () => {
    request.mockResolvedValue({ microphone: 'unavailable', voiceInput: false, blur: false });
    const wrapper = mountVisualApp();
    await flushPromises();
    expect(wrapper.classes()).toContain('tv-no-blur');
  });

  const mountApp = async (pinia = createPinia()) => {
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();
    return mount(TvApp, { global: { plugins: [pinia, router] } });
  };

  it('restores scale on native foreground notification even without a resize event', async () => {
    vi.stubGlobal('innerWidth', 960); vi.stubGlobal('innerHeight', 540);
    const wrapper = mountVisualApp();
    vi.stubGlobal('innerWidth', 1920); vi.stubGlobal('innerHeight', 1080);
    window.dispatchEvent(new CustomEvent('echo-tv-resume'));
    await nextTick();
    expect(wrapper.get<HTMLElement>('.tv-canvas').element.style.transform).toBe('scale(1)');
    expect(wrapper.get<HTMLElement>('.tv-canvas').element.style.getPropertyValue('--tv-content-bottom-safe')).toBe('152px');
  });

  it('does not replace the stable TV scale with a zero-size detached surface', async () => {
    vi.stubGlobal('innerWidth', 960); vi.stubGlobal('innerHeight', 540);
    const wrapper = mountVisualApp();
    vi.stubGlobal('innerWidth', 0); vi.stubGlobal('innerHeight', 0);
    window.dispatchEvent(new Event('resize'));
    await nextTick();
    expect(wrapper.get<HTMLElement>('.tv-canvas').element.style.transform).toBe('scale(0.5)');
  });

  it('renders the TV application landmark', async () => {
    const wrapper = await mountApp();
    expect(wrapper.get('[data-tv-app]').attributes('data-tv-app')).toBe('ready');
  });

  it('updates design-space padding together with the viewport scale on resize', async () => {
    vi.stubGlobal('innerWidth', 960);
    vi.stubGlobal('innerHeight', 540);
    const wrapper = await mountApp();
    const canvas = wrapper.get<HTMLElement>('.tv-canvas').element;
    expect(canvas.style.transform).toBe('scale(0.5)');
    expect(canvas.style.getPropertyValue('--tv-content-bottom-safe')).toBe('240px');
    vi.stubGlobal('innerWidth', 1920);
    vi.stubGlobal('innerHeight', 1080);
    window.dispatchEvent(new Event('resize'));
    await nextTick();
    expect(canvas.style.transform).toBe('scale(1)');
    expect(canvas.style.getPropertyValue('--tv-content-bottom-safe')).toBe('152px');
  });

  it('renders a safe-area region inside the logical TV canvas', async () => {
    const wrapper = await mountApp();
    const canvas = wrapper.get('.tv-canvas');
    const safeArea = wrapper.get('[data-tv-safe-area]');

    expect(safeArea.element.parentElement).toBe(canvas.element);
  });

  it('switches the complete shell to the opaque fallback when live blur is unavailable', async () => {
    const pinia = createPinia();
    const wrapper = await mountApp(pinia);
    const capabilities = useTvCapabilitiesStore(pinia);

    capabilities.capabilities = { microphone: 'unavailable', voiceInput: false, blur: false };
    capabilities.loaded = true;
    await nextTick();

    expect(wrapper.get('.tv-canvas').classes()).toContain('tv-no-blur');
  });
});
