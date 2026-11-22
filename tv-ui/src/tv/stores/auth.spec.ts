import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { useTvAuthStore } from './auth';

describe('tv-auth store projection states', () => {
  beforeEach(() => {
    window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
    installNativeClientForTest({ request: vi.fn(), on: () => () => undefined } as unknown as NativeClientLike);
    setActivePinia(createPinia());
  });

  it('accepts every Android projection state', () => {
    const auth = useTvAuthStore();
    for (const state of ['loggedOut', 'qrPending', 'scanned', 'expired', 'loggedIn'] as const) {
      auth.applyProjection({
        state,
        qrImageDataUrl: '',
        expiresAt: null,
        account: state === 'loggedIn' ? { userId: '42', nickname: 'Echo', avatar: '', membership: 'vip' } : null,
      });
      expect(auth.state).toBe(state);
    }
  });

  it('does not project a numeric fallback identifier onto the television', () => {
    const auth = useTvAuthStore();

    auth.applyProjection({
      state: 'loggedIn',
      account: { nickname: '用户408615481', membership: 'none' },
    });

    expect(auth.account?.displayName).toBe('酷狗用户');
  });

  it('subscribes to login events before the Android bridge attaches', async () => {
    delete window.EchoTvNative;
    let event!: (value: unknown) => void;
    installNativeClientForTest({
      request: () => Promise.resolve({ state: 'qrPending' }),
      on: (_name, listener) => { event = listener; return () => undefined; },
    } as NativeClientLike);
    const auth = useTvAuthStore();
    const disconnect = auth.connect();
    await auth.beginQr();
    event({ state: 'loggedIn', account: { nickname: 'Fixture', membership: 'none' } });
    expect(auth.state).toBe('loggedIn');
    disconnect();
  });

  it('rejects unknown states without corrupting the last valid projection', () => {
    const auth = useTvAuthStore();
    auth.applyProjection({ state: 'qrPending', qrImageDataUrl: '', expiresAt: null, account: null });
    expect(() => auth.applyProjection({ state: 'confirmed', qrImageDataUrl: '', expiresAt: null, account: null })).toThrow();
    expect(auth.state).toBe('qrPending');
  });

  it('late begin response after cancellation cannot resurrect the QR', async () => {
    let resolve!: (value: unknown) => void;
    installNativeClientForTest({ request: (method) => method === 'auth.beginQr'
      ? new Promise<unknown>((r) => { resolve = r; }) : Promise.resolve({ state: 'loggedOut' }), on: () => () => undefined } as NativeClientLike);
    const auth = useTvAuthStore();
    const begin = auth.beginQr();
    await auth.cancelQr();
    resolve({ state: 'qrPending', qrImageDataUrl: 'data:image/png;base64,LATE' });
    await begin;
    expect(auth.state).toBe('loggedOut');
    expect(auth.qrImageDataUrl).toBe('');
  });

  it('late old failure cannot overwrite a refreshed QR error state', async () => {
    let reject!: (error: unknown) => void;
    let calls = 0;
    installNativeClientForTest({ request: () => calls++ === 0 ? new Promise((_, r) => { reject = r; })
      : Promise.resolve({ state: 'qrPending', qrImageDataUrl: 'data:image/png;base64,NEW' }), on: () => () => undefined } as NativeClientLike);
    const auth = useTvAuthStore();
    const old = auth.beginQr().catch(() => undefined);
    await auth.beginQr();
    reject({ code: 'TIMEOUT', message: 'fixture' });
    await old;
    expect(auth.lastError).toBeNull();
    expect(auth.qrImageDataUrl).toContain('NEW');
  });

  it('late cancellation reply cannot clear a newly opened QR', async () => {
    let resolve!: (value: unknown) => void;
    installNativeClientForTest({ request: (method) => method === 'auth.cancelQr' ? new Promise<unknown>((r) => { resolve = r; })
      : Promise.resolve({ state: 'qrPending', qrImageDataUrl: 'data:image/png;base64,NEW' }), on: () => () => undefined } as NativeClientLike);
    const auth = useTvAuthStore();
    const cancel = auth.cancelQr();
    await auth.beginQr();
    resolve({ state: 'loggedOut' });
    await cancel;
    expect(auth.state).toBe('qrPending');
  });

  it('queued poll events after cancel are ignored', async () => {
    let event!: (value: unknown) => void;
    installNativeClientForTest({ request: (method) => Promise.resolve({ state: method === 'auth.beginQr' ? 'qrPending' : 'loggedOut' }),
      on: (_name, listener) => { event = listener; return () => undefined; } } as NativeClientLike);
    const auth = useTvAuthStore();
    const disconnect = auth.connect();
    await auth.beginQr();
    await auth.cancelQr();
    event({ state: 'loggedIn', account: { nickname: 'Late fixture', membership: 'none' } });
    expect(auth.state).toBe('loggedOut');
    disconnect();
  });

  it('a delayed begin reply cannot regress a newer scanned event', async () => {
    let event!: (value: unknown) => void;
    let resolve!: (value: unknown) => void;
    installNativeClientForTest({ request: () => new Promise<unknown>((r) => { resolve = r; }),
      on: (_name, listener) => { event = listener; return () => undefined; } } as NativeClientLike);
    const auth = useTvAuthStore();
    const disconnect = auth.connect();
    const begin = auth.beginQr();
    event({ state: 'scanned', qrImageDataUrl: 'data:image/png;base64,QR' });
    resolve({ state: 'qrPending' });
    await begin;
    expect(auth.state).toBe('scanned');
    disconnect();
  });
});
