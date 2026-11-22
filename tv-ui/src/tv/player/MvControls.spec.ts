import { createPinia } from 'pinia';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BackLayerRegistry, BACK_LAYER_REGISTRY_KEY, type BackLayer } from '../input/backCoordinator';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { createTvRouter } from '../router';
import MvDetailPage from '../pages/MvDetailPage.vue';
import MvControls from './MvControls.vue';

describe('MvControls', () => {
  const request = vi.fn();

  beforeEach(() => {
    vi.useFakeTimers();
    request.mockReset();
    request.mockImplementation((method: string) => {
      if (method === 'video.snapshot') {
        return Promise.resolve({ durationMs: 180_000, positionMs: 12_000, playing: true, quality: 'hd', errorCode: null, open: true, operation: null });
      }
      return Promise.resolve({ accepted: true, quality: 'hd' });
    });
    installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });
  });

  afterEach(() => vi.useRealTimers());

  it('uses real native qualities after HD fallback instead of keeping unavailable route tiers', async () => {
    request.mockImplementation((method: string) => Promise.resolve(method === 'video.snapshot'
      ? { durationMs: 180_000, positionMs: 1000, playing: true, quality: 'sd', availableQualities: ['sd', 'ld'], errorCode: null, open: true, operation: null }
      : { accepted: true, quality: 'hd', pending: true }));
    const wrapper = mount(MvControls, { props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd'] } });
    await flushPromises();
    expect(wrapper.find('[data-focus-id="mv-quality-hd"]').exists()).toBe(false);
    expect(wrapper.get('[data-focus-id="mv-quality-sd"]').classes()).toContain('active');
    expect(wrapper.find('[data-focus-id="mv-quality-ld"]').exists()).toBe(true);
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('reopens a failed MV from the stable play control instead of toggling an unopened player', async () => {
    request.mockImplementation((method: string) => Promise.resolve(method === 'video.snapshot'
      ? { durationMs: 0, positionMs: 0, playing: false, quality: '', errorCode: 'MV_RESOLVE_FAILED', open: false, operation: null }
      : { accepted: false, errorCode: 'MV_RESOLVE_FAILED' }));
    const wrapper = mount(MvControls, { props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd'] } });
    await flushPromises();
    request.mockClear();
    await wrapper.get('[data-focus-id="mv-toggle"]').trigger('click');
    await flushPromises();
    expect(request.mock.calls).toEqual([['video.open', { mvId: 'mv:42', quality: 'hd' }]]);
    wrapper.unmount();
  });

  it('rejects unknown native quality values without displaying native data', async () => {
    request.mockImplementation((method: string) => Promise.resolve(method === 'video.snapshot'
      ? { durationMs: 0, positionMs: 0, playing: false, quality: 'hd', availableQualities: ['https://secret.invalid'], errorCode: null, open: true, operation: null }
      : { accepted: true, quality: 'hd' }));
    const wrapper = mount(MvControls, { props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd'] } });
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain('当前 MV 暂时无法播放');
    expect(wrapper.html()).not.toContain('secret.invalid');
    wrapper.unmount();
  });

  it('opens by public MV id and renders only sanitized native state', async () => {
    const wrapper = mount(MvControls, {
      props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd', 'fhd'] },
    });
    await flushPromises();

    expect(request.mock.calls[0]).toEqual(['video.open', { mvId: 'mv:42', quality: 'hd' }]);
    expect(JSON.stringify(request.mock.calls[0])).not.toMatch(/https?:|hash|token/i);
    expect(wrapper.text()).toContain('0:12');
    expect(wrapper.text()).toContain('3:00');
    expect(wrapper.html()).not.toMatch(/https?:|SECRET_HASH|SECRET_TOKEN/i);
  });

  it('retries open only while the native route observer is catching up', async () => {
    let opens = 0;
    request.mockImplementation((method: string) => {
      if (method === 'video.open') {
        opens += 1;
        return Promise.resolve(opens === 1
          ? { accepted: false, errorCode: 'MV_ROUTE_REQUIRED' }
          : { accepted: true, quality: 'hd', pending: true });
      }
      if (method === 'video.snapshot') {
        return Promise.resolve({ durationMs: 0, positionMs: 0, playing: false, quality: '', errorCode: null, open: false, operation: 'open' });
      }
      return Promise.resolve({ accepted: true, quality: 'hd' });
    });

    const wrapper = mount(MvControls, {
      props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd'] },
    });
    await flushPromises();
    expect(opens).toBe(1);

    await vi.advanceTimersByTimeAsync(25);
    await flushPromises();

    expect(opens).toBe(2);
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    expect((wrapper.get('[data-focus-id="mv-quality-hd"]').element as HTMLButtonElement).disabled).toBe(true);
    wrapper.unmount();
  });

  it('maps focused D-pad controls to exact native commands and quality names', async () => {
    const wrapper = mount(MvControls, {
      props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd', 'fhd'] },
    });
    await flushPromises();
    request.mockClear();

    await wrapper.get('[data-focus-id="mv-progress"]').trigger('keydown', { key: 'ArrowLeft' });
    await wrapper.get('[data-focus-id="mv-progress"]').trigger('keydown', { key: 'ArrowRight' });
    await wrapper.get('[data-focus-id="mv-toggle"]').trigger('click');
    await wrapper.get('[data-focus-id="mv-quality-fhd"]').trigger('click');

    expect(request.mock.calls).toEqual([
      ['video.seekBy', { deltaMs: -10_000 }],
      ['video.seekBy', { deltaMs: 10_000 }],
      ['video.toggle', {}],
      ['video.setQuality', { quality: 'fhd' }],
    ]);
  });

  it('leaves native close ownership to the route lifecycle when controls are destroyed', async () => {
    const wrapper = mount(MvControls, { props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd'] } });
    await flushPromises();
    request.mockClear();

    wrapper.unmount();
    await flushPromises();

    expect(request.mock.calls).toEqual([]);
  });

  it('keeps snapshot polling single flight while a native response is pending', async () => {
    let finishPending!: (value: unknown) => void;
    const pending = new Promise((resolve) => { finishPending = resolve; });
    let snapshots = 0;
    request.mockImplementation((method: string) => {
      if (method === 'video.snapshot') {
        snapshots += 1;
        if (snapshots === 1) return Promise.resolve({ durationMs: 180_000, positionMs: 0, playing: true, quality: 'hd', errorCode: null, open: true, operation: null });
        return pending;
      }
      return Promise.resolve({ accepted: true, quality: 'hd' });
    });
    const wrapper = mount(MvControls, { props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd'] } });
    await flushPromises();

    await vi.advanceTimersByTimeAsync(2_000);
    expect(request.mock.calls.filter(([method]) => method === 'video.snapshot')).toHaveLength(2);

    finishPending({ durationMs: 180_000, positionMs: 10_000, playing: true, quality: 'hd', errorCode: null, open: true, operation: null });
    await flushPromises();
    await vi.advanceTimersByTimeAsync(499);
    expect(request.mock.calls.filter(([method]) => method === 'video.snapshot')).toHaveLength(2);
    wrapper.unmount();
  });

  it('keeps polling after a pending open and renders a later fixed resolver failure', async () => {
    let snapshots = 0;
    request.mockImplementation((method: string) => {
      if (method === 'video.open') return Promise.resolve({ accepted: true, pending: true, quality: 'hd' });
      if (method === 'video.snapshot') {
        snapshots += 1;
        if (snapshots === 1) {
          return Promise.resolve({ durationMs: 0, positionMs: 0, playing: false, quality: '', errorCode: null, open: false, operation: 'open' });
        }
        return Promise.resolve({
          durationMs: 0,
          positionMs: 0,
          playing: false,
          quality: '',
          errorCode: 'MV_RESOLVE_FAILED',
          open: false,
          operation: null,
          diagnostic: 'https://media.example.invalid/?token=SECRET_TOKEN',
        });
      }
      return Promise.resolve({ accepted: true });
    });
    const wrapper = mount(MvControls, { props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd'] } });
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);

    await vi.advanceTimersByTimeAsync(500);
    await flushPromises();

    expect(request.mock.calls.filter(([method]) => method === 'video.snapshot')).toHaveLength(2);
    expect(wrapper.get('[role="alert"]').text()).toContain('当前 MV 暂时无法播放');
    expect(wrapper.html()).not.toMatch(/media\.example|SECRET_TOKEN|diagnostic/i);
    wrapper.unmount();
  });

  it('disables quality while open is pending and enables it only after an open snapshot', async () => {
    let snapshots = 0;
    request.mockImplementation((method: string) => {
      if (method === 'video.open') return Promise.resolve({ accepted: true, quality: 'hd', pending: true });
      if (method === 'video.snapshot') {
        snapshots += 1;
        return Promise.resolve(snapshots === 1
          ? { durationMs: 0, positionMs: 0, playing: false, quality: '', errorCode: null, open: false, operation: 'open' }
          : { durationMs: 180_000, positionMs: 0, playing: true, quality: 'hd', errorCode: null, open: true, operation: null });
      }
      return Promise.resolve({ accepted: true, quality: 'fhd', pending: true });
    });
    const wrapper = mount(MvControls, { props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd', 'fhd'] } });
    await flushPromises();
    const quality = wrapper.get('[data-focus-id="mv-quality-fhd"]');

    expect((quality.element as HTMLButtonElement).disabled).toBe(true);
    await quality.trigger('click');
    expect(request.mock.calls.some(([method]) => method === 'video.setQuality')).toBe(false);

    await vi.advanceTimersByTimeAsync(500);
    await flushPromises();
    expect((quality.element as HTMLButtonElement).disabled).toBe(false);
    await quality.trigger('click');
    expect(request).toHaveBeenLastCalledWith('video.setQuality', { quality: 'fhd' });
    wrapper.unmount();
  });

  it('shows a fixed resolver error without reflecting native secrets', async () => {
    request.mockImplementation((method: string) => {
      if (method === 'video.open') return Promise.resolve({ accepted: false, errorCode: 'MV_RESOLVE_FAILED' });
      if (method === 'video.snapshot') {
        return Promise.resolve({
          durationMs: 0,
          positionMs: 0,
          playing: false,
          quality: 'hd',
          errorCode: 'MV_RESOLVE_FAILED',
          playbackUrl: 'https://media.example.invalid/?token=SECRET_TOKEN',
        });
      }
      return Promise.resolve({ accepted: false });
    });

    const wrapper = mount(MvControls, { props: { mvId: 'mv:bad', initialQuality: 'hd', qualities: ['hd'] } });
    await flushPromises();

    expect(wrapper.get('[role="alert"]').text()).toContain('当前 MV 暂时无法播放');
    expect(wrapper.html()).not.toMatch(/media\.example|SECRET_TOKEN|playbackUrl/i);
  });

  it('fails closed on an oversized resolver response without storing native diagnostics', async () => {
    request.mockImplementation((method: string) => {
      if (method === 'video.open') {
        return Promise.resolve({
          accepted: false,
          errorCode: 'MV_RESOLVER_BUSY',
          diagnostic: 'https://media.example.invalid/?token=SECRET_TOKEN',
        });
      }
      return Promise.resolve({ durationMs: 0, positionMs: 0, playing: false, quality: 'hd', errorCode: null, open: false, operation: null });
    });

    const wrapper = mount(MvControls, { props: { mvId: 'mv:busy', initialQuality: 'hd', qualities: ['hd'] } });
    await flushPromises();

    const state = wrapper.vm as unknown as { snapshot: { errorCode: string | null } };
    expect(state.snapshot.errorCode).toBe('MV_UNAVAILABLE');
    expect(wrapper.get('[role="alert"]').text()).toContain('当前 MV 暂时无法播放');
    expect(JSON.stringify(state.snapshot) + wrapper.html()).not.toMatch(/media\.example|SECRET_TOKEN|diagnostic|https?:/i);
  });

  it('fails closed on unknown command result fields', async () => {
    request.mockImplementation((method: string) => {
      if (method === 'video.snapshot') {
        return Promise.resolve({ durationMs: 180_000, positionMs: 0, playing: true, quality: 'hd', errorCode: null, open: true, operation: null });
      }
      if (method === 'video.toggle') {
        return Promise.resolve({ accepted: true, quality: 'hd', secret: 'must-not-enter-state' });
      }
      return Promise.resolve({ accepted: true, quality: 'hd' });
    });
    const wrapper = mount(MvControls, { props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd'] } });
    await flushPromises();

    await wrapper.get('[data-focus-id="mv-toggle"]').trigger('click');
    await flushPromises();

    const state = wrapper.vm as unknown as { snapshot: { errorCode: string | null } };
    expect(state.snapshot.errorCode).toBe('MV_COMMAND_FAILED');
    expect(JSON.stringify(state.snapshot) + wrapper.html()).not.toContain('must-not-enter-state');
  });

  it('never stores an arbitrary native error string in Vue state', async () => {
    request.mockImplementation((method: string) => {
      if (method === 'video.snapshot') {
        return Promise.resolve({
          durationMs: 0,
          positionMs: 0,
          playing: false,
          quality: 'hd',
          errorCode: 'https://media.example.invalid/?token=SECRET_TOKEN',
          open: true,
          operation: null,
        });
      }
      return Promise.resolve({ accepted: true, quality: 'hd' });
    });
    const wrapper = mount(MvControls, { props: { mvId: 'mv:42', initialQuality: 'hd', qualities: ['hd'] } });
    await flushPromises();

    const state = wrapper.vm as unknown as { snapshot: { errorCode: string | null } };
    expect(state.snapshot.errorCode).toBe('MV_UNAVAILABLE');
    expect(JSON.stringify(state.snapshot)).not.toMatch(/https?:|SECRET|token/i);
  });
});

describe('MvDetailPage Back ownership', () => {
  it('focuses the first available MV control on entry', async () => {
    vi.useRealTimers();
    const request = vi.fn((method: string) => {
      if (method === 'video.snapshot') {
        return Promise.resolve({ durationMs: 0, positionMs: 0, playing: false, quality: 'hd', errorCode: null, open: true, operation: null });
      }
      return Promise.resolve({ accepted: true, quality: 'hd' });
    });
    installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });
    const router = createTvRouter();
    await router.push('/mv/mv%3A42?quality=hd');
    await router.isReady();
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = mount(MvDetailPage, {
      attachTo: document.body,
      global: {
        plugins: [createPinia(), router],
        directives: { tvFocus: {} },
        provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: registry },
      },
    });
    await flushPromises();
    await nextTick();

    expect((document.activeElement as HTMLElement | null)?.dataset.focusId).toBe('mv-progress');
    wrapper.unmount();
  });

  it('waits for native close, returns to the source page, and restores the source card focus', async () => {
    vi.useRealTimers();
    let finishClose!: (value: { accepted: boolean }) => void;
    const closeResult = new Promise<{ accepted: boolean }>((resolve) => { finishClose = resolve; });
    const request = vi.fn((method: string) => {
      if (method === 'video.close') return closeResult;
      if (method === 'video.snapshot') return Promise.resolve({ durationMs: 0, positionMs: 0, playing: false, quality: 'hd', errorCode: null, open: false, operation: null });
      return Promise.resolve({ accepted: true, quality: 'hd' });
    });
    installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });

    const router = createTvRouter();
    await router.push('/home');
    await router.push('/mv/mv%3A42?title=Blue&artist=Echo&quality=hd&qualities=hd,fhd&sourceFocusId=mv-source-42');
    await router.isReady();
    const restored: string[] = [];
    const registry = new BackLayerRegistry((id) => restored.push(id));
    let mvLayer: BackLayer | undefined;
    const register = vi.spyOn(registry, 'register').mockImplementation((layer, focusId) => {
      mvLayer = layer;
      return BackLayerRegistry.prototype.register.call(registry, layer, focusId);
    });
    const wrapper = mount({ template: '<RouterView />' }, {
      global: { plugins: [createPinia(), router], provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: registry } },
    });
    await flushPromises();

    expect(register).toHaveBeenCalledWith(expect.objectContaining({ kind: 'drawer' }), 'mv-source-42');
    mvLayer!.close();
    await nextTick();
    expect(router.currentRoute.value.name).toBe('mv-detail');

    finishClose({ accepted: true });
    await flushPromises();
    expect(router.currentRoute.value.name).toBe('home');
    wrapper.unmount();
    await nextTick();
    expect(restored).toEqual(['mv-source-42']);
  });

  it.each(['BRIDGE_BUSY', 'BRIDGE_TIMEOUT'])('does not navigate or release source focus when close fails with %s', async (errorCode) => {
    vi.useRealTimers();
    const request = vi.fn((method: string) => {
      if (method === 'video.close') return Promise.reject({ code: errorCode, message: 'fixed', retryable: true, scope: 'bridge' });
      if (method === 'video.snapshot') return Promise.resolve({ durationMs: 0, positionMs: 0, playing: false, quality: 'hd', errorCode: null, open: false, operation: null });
      return Promise.resolve({ accepted: true, quality: 'hd' });
    });
    installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });
    const router = createTvRouter();
    await router.push('/home');
    await router.push('/mv/mv%3A42?quality=hd&sourceFocusId=recent-tv_real');
    await router.isReady();
    const restored: string[] = [];
    const registry = new BackLayerRegistry((id) => restored.push(id));
    let mvLayer: BackLayer | undefined;
    vi.spyOn(registry, 'register').mockImplementation((layer, focusId) => {
      mvLayer = layer;
      return BackLayerRegistry.prototype.register.call(registry, layer, focusId);
    });
    const wrapper = mount(MvDetailPage, {
      global: { plugins: [createPinia(), router], provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: registry } },
    });
    await flushPromises();

    mvLayer!.close();
    await flushPromises();

    expect(router.currentRoute.value.name).toBe('mv-detail');
    expect(restored).toEqual([]);
    wrapper.unmount();
  });

  it('fails closed when native close returns unknown fields', async () => {
    vi.useRealTimers();
    const request = vi.fn((method: string) => {
      if (method === 'video.close') return Promise.resolve({ accepted: true, secret: 'unapproved' });
      if (method === 'video.snapshot') {
        return Promise.resolve({ durationMs: 0, positionMs: 0, playing: false, quality: 'hd', errorCode: null, open: true, operation: null });
      }
      return Promise.resolve({ accepted: true, quality: 'hd' });
    });
    installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });
    const router = createTvRouter();
    await router.push('/home');
    await router.push('/mv/mv%3A42?quality=hd&sourceFocusId=recent-tv_real');
    await router.isReady();
    const registry = new BackLayerRegistry(() => undefined);
    let layer: BackLayer | undefined;
    vi.spyOn(registry, 'register').mockImplementation((next, focusId) => {
      layer = next;
      return BackLayerRegistry.prototype.register.call(registry, next, focusId);
    });
    const wrapper = mount(MvDetailPage, {
      global: { plugins: [createPinia(), router], provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: registry } },
    });
    await flushPromises();

    layer!.close();
    await flushPromises();

    expect(router.currentRoute.value.name).toBe('mv-detail');
    wrapper.unmount();
  });

  it('blocks programmatic route leave until native close succeeds', async () => {
    vi.useRealTimers();
    let finishClose!: (value: { accepted: boolean }) => void;
    const closeResult = new Promise<{ accepted: boolean }>((resolve) => { finishClose = resolve; });
    const request = vi.fn((method: string) => {
      if (method === 'video.close') return closeResult;
      if (method === 'video.snapshot') return Promise.resolve({ durationMs: 0, positionMs: 0, playing: false, quality: 'hd', errorCode: null, open: false, operation: null });
      return Promise.resolve({ accepted: true, quality: 'hd' });
    });
    installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });
    const router = createTvRouter();
    await router.push('/home');
    await router.push('/mv/mv%3A42?quality=hd&sourceFocusId=recent-tv_real');
    await router.isReady();
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = mount({ template: '<RouterView />' }, {
      global: { plugins: [createPinia(), router], provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: registry } },
    });
    await flushPromises();

    const leaving = router.push('/home');
    await flushPromises();
    expect(router.currentRoute.value.name).toBe('mv-detail');
    finishClose({ accepted: true });
    await leaving;

    expect(router.currentRoute.value.name).toBe('home');
    expect(request.mock.calls.filter(([method]) => method === 'video.close')).toHaveLength(1);
    wrapper.unmount();
  });
});
