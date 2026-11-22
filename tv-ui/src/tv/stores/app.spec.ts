import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { useTvAppStore } from './app';

describe('startup app session projection', () => {
  beforeEach(() => setActivePinia(createPinia()));
  afterEach(() => delete window.EchoTvNative);

  it('requests a snapshot even when Android attaches the bridge late', async () => {
    delete window.EchoTvNative;
    const request = vi.fn().mockResolvedValue({ route: 'home', session: { status: 'loggedIn', displayName: 'Fixture' } });
    installNativeClientForTest({ request, on: () => () => undefined });
    const app = useTvAppStore();
    await app.loadSnapshot();
    expect(request).toHaveBeenCalledWith('app.getSnapshot', {});
    expect(app.session.status).toBe('loggedIn');
  });

  it('does not let the pre-restore snapshot overwrite the restored account', async () => {
    window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
    let resolveOld!: (value: unknown) => void;
    let requests = 0;
    installNativeClientForTest({ request: () => ++requests === 1
      ? new Promise<unknown>(r => { resolveOld = r; })
      : Promise.resolve({ route: 'home', session: { status: 'loggedIn', displayName: 'Fixture' } }),
      on: () => () => undefined } as NativeClientLike);
    const app = useTvAppStore();
    const old = app.loadSnapshot();
    await app.loadSnapshot();
    resolveOld({ route: 'home', session: { status: 'loggedOut' } });
    expect((await old).session.status).toBe('loggedIn');
    expect(app.session.status).toBe('loggedIn');
  });
});
