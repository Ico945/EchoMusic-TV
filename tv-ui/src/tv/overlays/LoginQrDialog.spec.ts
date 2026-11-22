import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import { createTvRouter } from '../router';
import { useTvAuthStore } from '../stores/auth';
import LoginQrDialog from './LoginQrDialog.vue';
import { tvFocusRegistry } from '../focus/directive';

function installAuthClient(handler?: (method: string) => unknown) {
  const client: NativeClientLike = {
    request: vi.fn((method: string) => {
      if (handler) return Promise.resolve(handler(method));
      if (method === 'auth.beginQr') {
        return Promise.resolve({
          state: 'qrPending',
          qrImageDataUrl: 'data:image/png;base64,QR',
          expiresAt: 1_700_000_000_000,
          account: null,
        });
      }
      if (method === 'auth.cancelQr') return Promise.resolve({ state: 'loggedOut', qrImageDataUrl: '', expiresAt: null, account: null });
      if (method === 'auth.restore') return Promise.resolve({ state: 'loggedOut', qrImageDataUrl: '', expiresAt: null, account: null });
      if (method === 'auth.logout') return Promise.resolve({ state: 'loggedOut', qrImageDataUrl: '', expiresAt: null, account: null });
      return Promise.resolve({});
    }) as NativeClientLike['request'],
    on: vi.fn(() => () => undefined),
  };
  installNativeClientForTest(client);
  return client;
}

function mountDialog(registry: BackLayerRegistry, sourceFocusId?: string) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createTvRouter();
  return mount(LoginQrDialog, {
    props: { open: true, sourceFocusId },
    global: {
      plugins: [pinia, router],
      directives: { tvFocus: {} },
      provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: registry },
    },
  });
}

describe('LoginQrDialog', () => {
  beforeEach(() => {
    window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  });
  afterEach(() => { delete window.EchoTvNative; vi.restoreAllMocks(); });

  it('Back cancels native polling and restores focus without waiting for its reply', async () => {
    const client = installAuthClient((method) => method === 'auth.cancelQr'
      ? new Promise(() => undefined)
      : { state: 'qrPending', qrImageDataUrl: 'data:image/png;base64,QR' });
    const restored: string[] = [];
    const registry = new BackLayerRegistry((id) => restored.push(id));
    const wrapper = mountDialog(registry, 'nav-account');
    await flushPromises();
    registry.dispatch();
    await flushPromises();
    expect(client.request).toHaveBeenCalledWith('auth.cancelQr', {});
    expect(wrapper.emitted('close')).toHaveLength(1);
    expect(restored).toEqual(['nav-account']);
    wrapper.unmount();
    expect((client.request as ReturnType<typeof vi.fn>).mock.calls.filter((c) => c[0] === 'auth.cancelQr')).toHaveLength(1);
  });

  it('parent close cancels an in-progress QR flow', async () => {
    const client = installAuthClient();
    const wrapper = mountDialog(new BackLayerRegistry(() => undefined));
    await flushPromises();
    await wrapper.setProps({ open: false });
    expect(client.request).toHaveBeenCalledWith('auth.cancelQr', {});
    wrapper.unmount();
  });

  it('unmount cancels an in-progress QR flow', async () => {
    const client = installAuthClient();
    const wrapper = mountDialog(new BackLayerRegistry(() => undefined));
    await flushPromises();
    wrapper.unmount();
    expect(client.request).toHaveBeenCalledWith('auth.cancelQr', {});
  });

  it('late QR creation after Back cannot steal focus or confirm the closed dialog', async () => {
    let resolve!: (value: unknown) => void;
    installAuthClient((method) => method === 'auth.beginQr' ? new Promise((r) => { resolve = r; }) : { state: 'loggedOut' });
    const focus = vi.spyOn(tvFocusRegistry, 'restore').mockReturnValue(true);
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = mountDialog(registry);
    registry.dispatch();
    await flushPromises();
    focus.mockClear();
    resolve({ state: 'qrPending', qrImageDataUrl: 'data:image/png;base64,LATE' });
    await flushPromises();
    expect(focus).not.toHaveBeenCalled();
    useTvAuthStore().applyProjection({ state: 'loggedIn', account: { nickname: 'Fixture', membership: 'none' } });
    await flushPromises();
    expect(wrapper.emitted('confirmed')).toBeUndefined();
    wrapper.unmount();
  });

  it('expired QR restores focus to regenerate instead of the removed image', async () => {
    installAuthClient();
    const focus = vi.spyOn(tvFocusRegistry, 'restore').mockReturnValue(true);
    const wrapper = mountDialog(new BackLayerRegistry(() => undefined));
    await flushPromises();
    focus.mockClear();
    useTvAuthStore().applyProjection({ state: 'expired' });
    await flushPromises();
    expect(focus).toHaveBeenCalledWith('login-regenerate');
    wrapper.unmount();
  });

  it('successful confirmation does not send a cancellation on parent close or unmount', async () => {
    const client = installAuthClient();
    const wrapper = mountDialog(new BackLayerRegistry(() => undefined));
    await flushPromises();
    useTvAuthStore().applyProjection({ state: 'loggedIn', account: { nickname: 'Fixture', membership: 'none' } });
    await flushPromises();
    expect(wrapper.emitted('confirmed')).toHaveLength(1);
    await wrapper.setProps({ open: false });
    wrapper.unmount();
    expect(client.request).not.toHaveBeenCalledWith('auth.cancelQr', {});
  });

  it('cancel failure does not trap the dialog or reject a DOM event handler', async () => {
    installAuthClient((method) => method === 'auth.cancelQr' ? Promise.reject({ code: 'TIMEOUT', message: 'fixture' })
      : { state: 'qrPending', qrImageDataUrl: 'data:image/png;base64,QR' });
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = mountDialog(registry);
    await flushPromises();
    registry.dispatch();
    await flushPromises();
    expect(wrapper.find('.login-dialog').exists()).toBe(false);
    expect(wrapper.emitted('close')).toHaveLength(1);
    wrapper.unmount();
  });

  it('shows Concept App instructions and the waiting QR', async () => {
    installAuthClient();
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = mountDialog(registry);
    await flushPromises();
    expect(wrapper.text()).toContain('酷狗概念版 App');
    expect(wrapper.text()).toContain('等待扫码');
    expect(wrapper.get('img.qr').attributes('src')).toContain('data:image/png;base64');
  });

  it('regenerates an expired QR', async () => {
    const client = installAuthClient((method) => {
      if (method === 'auth.beginQr') return { state: 'expired', qrImageDataUrl: '', expiresAt: null, account: null };
      return {};
    });
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = mountDialog(registry);
    await flushPromises();
    expect(wrapper.text()).toContain('二维码已过期');
    await wrapper.get('[data-action="regenerate-qr"]').trigger('click');
    await flushPromises();
    const beginCalls = (client.request as ReturnType<typeof vi.fn>).mock.calls.filter((call) => call[0] === 'auth.beginQr');
    expect(beginCalls.length).toBeGreaterThanOrEqual(2);
  });

  it('closes and restores the source focus when login is confirmed', async () => {
    let state: unknown = { state: 'qrPending', qrImageDataUrl: 'data:image/png;base64,QR', expiresAt: null, account: null };
    installAuthClient((method) => {
      if (method === 'auth.beginQr') return state;
      return {};
    });
    const restored: string[] = [];
    const registry = new BackLayerRegistry((id) => restored.push(id));
    const wrapper = mountDialog(registry, 'nav-account');
    await flushPromises();

    const auth = useTvAuthStore();
    state = { state: 'loggedIn', qrImageDataUrl: '', expiresAt: null, account: { userId: '42', nickname: 'Echo User', avatar: '', membership: 'vip' } };
    auth.applyProjection(state);
    await flushPromises();
    await flushPromises();

    expect(restored).toEqual(['nav-account']);
    expect(wrapper.emitted('confirmed')).toBeTruthy();
  });

  it('cancel sends auth.cancelQr and closes', async () => {
    const client = installAuthClient();
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = mountDialog(registry);
    await flushPromises();
    await wrapper.get('[data-focus-id="login-cancel"]').trigger('click');
    await flushPromises();
    expect((client.request as ReturnType<typeof vi.fn>).mock.calls.some((call) => call[0] === 'auth.cancelQr')).toBe(true);
    expect(wrapper.emitted('close')).toBeTruthy();
  });
});
