import { createPinia } from 'pinia';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installNativeClientForTest } from '../native/client';
import { createTvRouter } from '../router';
import { useTvAppStore } from '../stores/app';
import { useTvAuthStore } from '../stores/auth';
import TvShell from './TvShell.vue';
import { vTvFocus } from '../focus/directive';

enableAutoUnmount(afterEach);
afterEach(() => delete window.EchoTvNative);
const request = vi.fn();
const restored = { state: 'loggedIn', account: { nickname: 'Saved account fixture', membership: 'none' } };
beforeEach(() => {
  request.mockReset();
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  request.mockImplementation((method: string) => Promise.resolve(method === 'auth.restore' ? restored
    : method === 'app.getSnapshot' ? { route: 'home', session: { status: 'loggedIn', displayName: 'Saved account fixture' } }
      : { accepted: true }));
  installNativeClientForTest({ request, on: () => () => undefined });
});

async function render() {
  const pinia = createPinia();
  const router = createTvRouter();
  await router.push('/home');
  await router.isReady();
  const wrapper = mount(TvShell, { global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
  return { wrapper, pinia };
}

describe('TV session startup', () => {
  it('restores persisted auth once and refreshes the displayed account without opening a QR', async () => {
    const { wrapper, pinia } = await render();
    await flushPromises();
    expect(request.mock.calls.filter(([method]) => method === 'auth.restore')).toHaveLength(1);
    expect(useTvAuthStore(pinia).state).toBe('loggedIn');
    expect(useTvAppStore(pinia).session.status).toBe('loggedIn');
    expect(wrapper.get('[data-focus-id=account] > span:last-child').text()).toBe('账户');
    expect(wrapper.get('[data-focus-id=account]').attributes('aria-label')).toBe('Saved account fixture');
    expect(wrapper.find('.login-dialog').exists()).toBe(false);
    expect(request.mock.calls.some(([method]) => method === 'auth.beginQr')).toBe(false);
  });

  it('keeps navigation available when restore fails', async () => {
    request.mockImplementation((method: string) => method === 'auth.restore'
      ? Promise.reject({ code: 'BRIDGE_TIMEOUT', message: 'fixture' })
      : Promise.resolve({ accepted: true }));
    const { wrapper, pinia } = await render();
    await flushPromises();
    expect(request).toHaveBeenCalledWith('auth.restore', {});
    expect(useTvAuthStore(pinia).state).toBe('loggedOut');
    expect(wrapper.findAll('[data-nav-id]')).toHaveLength(6);
    expect(wrapper.find('.login-dialog').exists()).toBe(false);
  });

  it('does not refresh the app from a restore that finishes after unmount', async () => {
    let resolve!: (value: unknown) => void;
    request.mockImplementation((method: string) => method === 'auth.restore'
      ? new Promise(r => { resolve = r; }) : Promise.resolve({ accepted: true }));
    const { wrapper } = await render();
    expect(request).toHaveBeenCalledWith('auth.restore', {});
    wrapper.unmount();
    const before = request.mock.calls.filter(([method]) => method === 'app.getSnapshot').length;
    resolve(restored);
    await flushPromises();
    expect(request.mock.calls.filter(([method]) => method === 'app.getSnapshot')).toHaveLength(before);
  });
});
