import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { useTvAppStore } from './app';
import { useTvContentStore } from './content';
import { parseTvSession, parseTvSnapshot } from './parsers';

const sessionStatuses = ['loggedOut', 'expired', 'qrPending', 'loggedIn'] as const;
const tvRoutes = ['home', 'explore', 'fm', 'ranking', 'library', 'search', 'more'] as const;

type HomePayload = {
  greeting: string;
  hero: TrackPayload;
  secondary: TrackPayload[];
  shelves: Array<{ id: string; title: string; capability: string; tracks: TrackPayload[] }>;
};

type TrackPayload = {
  id: string;
  title: string;
  artist: string;
  album: string;
  artwork: string;
  durationMs: number;
  mv?: { id: string; title: string; artist: string; quality: string; qualities: string[] };
};

const track = (id: string): TrackPayload => ({
  id,
  title: `${id} title`,
  artist: `${id} artist`,
  album: `${id} album`,
  artwork: `${id} artwork`,
  durationMs: 210_000,
});

const home = (label: string): HomePayload => ({
  greeting: `${label} greeting`,
  hero: track(`${label}-hero`),
  secondary: [track(`${label}-secondary`)],
  shelves: [{ id: `${label}-shelf`, title: `${label} shelf`, capability: 'play', tracks: [track(`${label}-track`)] }],
});

function installRequestHandler(handler: (method: string) => Promise<unknown>) {
  const client: NativeClientLike = {
    request: <T>(method: string) => handler(method) as Promise<T>,
    on: () => () => undefined,
  };
  installNativeClientForTest(client);
}

function loadHome(store: ReturnType<typeof useTvContentStore>, bootstrapHome?: HomePayload): Promise<void> {
  return (store as unknown as { loadHome: (bootstrap?: HomePayload) => Promise<void> }).loadHome(bootstrapHome);
}

function loadSnapshot(store: ReturnType<typeof useTvAppStore>): Promise<{ route: string; session: { status: string; displayName?: string; membership?: string }; bootstrapHome?: HomePayload }> {
  return (store as unknown as { loadSnapshot: () => Promise<{ route: string; session: { status: string; displayName?: string; membership?: string }; bootstrapHome?: HomePayload }> }).loadSnapshot();
}

describe('TV content home loading', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  });

  afterEach(() => {
    delete window.EchoTvNative;
  });

  it('a late earlier home response cannot replace the newer page or its focus targets', async () => {
    const pending: Array<(value: unknown) => void> = [];
    installRequestHandler(() => new Promise(resolve => pending.push(resolve)));
    const store = useTvContentStore();
    const old = store.loadHome();
    const latest = store.loadHome();
    pending[1](home('latest'));
    await latest;
    pending[0](home('old'));
    await old;
    expect(store.home?.hero.id).toBe('latest-hero');
    expect(store.status).toBe('ready');
  });

  it('renders Android-shaped snapshot bootstrapHome before the real home response settles (catches a blank first paint)', () => {
    const bootstrap = home('bootstrap');
    let resolveHome: ((value: unknown) => void) | undefined;
    installRequestHandler(
      (method) => {
        if (method === 'app.getSnapshot') {
          return Promise.resolve({ route: 'home', session: { status: 'loggedIn', displayName: 'Echo', membership: 'vip' }, bootstrapHome: bootstrap });
        }
        return new Promise((resolve) => {
          resolveHome = resolve;
        });
      },
    );
    const app = useTvAppStore();
    const content = useTvContentStore();

    return loadSnapshot(app).then((snapshot) => {
      const loading = loadHome(content, snapshot.bootstrapHome);
      expect(content.home).toEqual(bootstrap);
      resolveHome?.(home('real'));
      return loading;
    });
  });

  it('replaces bootstrap shelves with the real home response (catches stale bootstrap content)', async () => {
    const realHome = home('real');
    installRequestHandler(async () => realHome);
    const content = useTvContentStore();

    await loadHome(content, home('bootstrap'));

    expect(content.home).toEqual(realHome);
  });

  it('preserves bootstrap and previously loaded shelves when real home requests fail (catches destructive error handling)', async () => {
    const bootstrap = home('bootstrap');
    const realHome = home('real');
    const responses: Array<HomePayload | Error> = [new Error('unavailable'), realHome, new Error('unavailable')];
    installRequestHandler(async () => {
      const response = responses.shift();
      if (response instanceof Error) throw response;
      return response;
    });
    const content = useTvContentStore();

    await loadHome(content, bootstrap);
    expect(content.home).toEqual(bootstrap);

    await loadHome(content);
    expect(content.home).toEqual(realHome);

    await loadHome(content);
    expect(content.home).toEqual(realHome);
  });

  it('does not invent local content when the native bridge is unavailable', async () => {
    delete window.EchoTvNative;
    installRequestHandler(() => new Promise(() => undefined));
    const content = useTvContentStore();

    await expect(loadHome(content)).resolves.toBeUndefined();
    expect(content.home).toBeNull();
    expect(content.status).toBe('error');
    expect(content.errorMessage).toBe('请在 Android TV 应用中打开');
  });

  it('shows a retryable error instead of synthetic content when the first native request fails', async () => {
    installRequestHandler(async () => {
      throw new Error('upstream unavailable');
    });
    const content = useTvContentStore();

    await loadHome(content);

    expect(content.home).toBeNull();
    expect(content.status).toBe('error');
    expect(content.errorMessage).toBe('内容加载失败，请重试');
  });

  it('keeps the local snapshot when the Android session is malformed (catches invalid session state)', async () => {
    installRequestHandler(async () => ({ route: 'library', session: { status: 'signed-in' } }));
    const app = useTvAppStore();

    await expect(loadSnapshot(app)).resolves.toEqual({ route: 'home', session: { status: 'loggedOut' } });
    expect(app.route).toBe('home');
    expect(app.session).toEqual({ status: 'loggedOut' });
  });

  it('drops a malformed bootstrapHome from the Android snapshot (catches unchecked bootstrap payloads)', async () => {
    const invalidBootstrap = { ...home('bootstrap'), secondary: track('not-an-array') };
    installRequestHandler(async () => ({ route: 'home', session: { status: 'loggedIn' }, bootstrapHome: invalidBootstrap }));
    const app = useTvAppStore();

    await expect(loadSnapshot(app)).resolves.toEqual({ route: 'home', session: { status: 'loggedOut' } });
    expect(app.session).toEqual({ status: 'loggedOut' });
  });

  it('keeps only a typed native MV detail and never invents one for ordinary tracks', () => {
    const payload = home('native');
    payload.hero.mv = {
      id: 'mv:9001',
      title: 'Native MV',
      artist: 'Native Artist',
      quality: 'hd',
      qualities: ['hd', 'fhd'],
    };

    const parsed = parseTvSnapshot({ route: 'home', session: { status: 'loggedIn' }, bootstrapHome: payload });

    expect(parsed.bootstrapHome?.hero.mv).toEqual(payload.hero.mv);
    expect(parsed.bootstrapHome?.secondary[0].mv).toBeUndefined();
  });

  it('preserves visible content when a real home track has an invalid duration (catches malformed track payloads)', async () => {
    const bootstrap = home('bootstrap');
    const invalidHome = { ...home('real'), hero: { ...track('invalid-duration'), durationMs: -1 } };
    installRequestHandler(async () => invalidHome);
    const content = useTvContentStore();

    await loadHome(content, bootstrap);

    expect(content.home).toEqual(bootstrap);
  });

  it('preserves visible content when a real home shelf has non-array tracks (catches malformed shelf payloads)', async () => {
    const bootstrap = home('bootstrap');
    const invalidHome = { ...home('real'), shelves: [{ id: 'bad-shelf', title: 'Bad shelf', capability: 'play', tracks: 'not-an-array' }] };
    installRequestHandler(async () => invalidHome);
    const content = useTvContentStore();

    await loadHome(content, bootstrap);

    expect(content.home).toEqual(bootstrap);
  });

  it('preserves visible content when secondary is not an array (catches Android home shape drift)', async () => {
    const bootstrap = home('bootstrap');
    const invalidHome = { ...home('real'), secondary: track('not-an-array') };
    installRequestHandler(async () => invalidHome);
    const content = useTvContentStore();

    await loadHome(content, bootstrap);

    expect(content.home).toEqual(bootstrap);
  });

  it.each(sessionStatuses)('accepts Android session status %s directly', (status) => {
    expect(parseTvSession({ status })).toEqual({ status });
  });

  it('replaces a legacy numeric account fallback with a shared-screen-safe label', () => {
    expect(parseTvSession({ status: 'loggedIn', displayName: '#408615481' })).toEqual({
      status: 'loggedIn',
      displayName: '酷狗用户',
    });
  });

  it.each(['signed-in', 'guest'] as const)('rejects unsupported session status %s directly', (status) => {
    expect(() => parseTvSession({ status })).toThrow('Session status');
  });

  it.each(tvRoutes)('accepts TV route %s directly', (route) => {
    expect(parseTvSnapshot({ route, session: { status: 'loggedIn' } })).toEqual({ route, session: { status: 'loggedIn' } });
  });

  it.each(['settings', 'profile'] as const)('rejects unsupported TV route %s directly', (route) => {
    expect(() => parseTvSnapshot({ route, session: { status: 'loggedIn' } })).toThrow('Snapshot route');
  });

  it.each(tvRoutes)('accepts TV route %s through the app snapshot boundary', async (route) => {
    installRequestHandler(async () => ({ route, session: { status: 'loggedIn' } }));
    const app = useTvAppStore();

    await expect(loadSnapshot(app)).resolves.toEqual({ route, session: { status: 'loggedIn' } });
    expect(app.route).toBe(route);
  });

  it('keeps the loggedOut fallback when an unsupported route crosses the app snapshot boundary', async () => {
    installRequestHandler(async () => ({ route: 'settings', session: { status: 'loggedIn' } }));
    const app = useTvAppStore();

    await expect(loadSnapshot(app)).resolves.toEqual({ route: 'home', session: { status: 'loggedOut' } });
    expect(app.route).toBe('home');
    expect(app.session).toEqual({ status: 'loggedOut' });
  });
});
