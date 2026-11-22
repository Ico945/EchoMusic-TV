import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createTvRouter } from '../router';
import { tvFocusRegistry, vTvFocus } from '../focus/directive';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import { installNativeClientForTest } from '../native/client';
import { useTvDetailStore } from '../stores/detail';
import { useTvPlayerStore } from '../stores/player';
import SongDetailPage from './SongDetailPage.vue';

const song = (id = 'fixture') => ({ id, kind: 'song', title: 'Fixture song', artwork: '', tabs: ['info', 'comments'],
  track: { id, title: 'Fixture song', artist: 'Artist', album: 'Album', artwork: '', durationMs: 200000 },
  related: { items: [], nextCursor: null, total: null } });
let wrapper: VueWrapper | undefined;
let nav: HTMLButtonElement | undefined;
afterEach(() => { wrapper?.unmount(); wrapper = undefined; if (nav) { tvFocusRegistry.unregister(nav); nav.remove(); } nav = undefined; delete window.EchoTvNative; });

async function setup(initial: Promise<unknown> = Promise.reject(new Error('fixture failure'))) {
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  // Attach the rejection handler before awaiting router startup.
  const settled = initial.then(value => ({ value }), () => ({ failed: true }));
  const pinia = createPinia(); setActivePinia(pinia);
  const request = vi.fn(async () => { const result = await settled; if ('failed' in result) throw new Error('fixture failure'); return result.value; });
  installNativeClientForTest({ request: <T>() => request() as Promise<T>, on: () => () => undefined });
  const router = createTvRouter(); await router.push({ name: 'song-detail', params: { id: 'fixture' }, query: { sourceFocusId: 'source-card' } }); await router.isReady();
  nav = document.createElement('button'); nav.dataset.focusId = 'nav-home'; document.body.append(nav); tvFocusRegistry.register(nav);
  const back = new BackLayerRegistry(() => undefined);
  wrapper = mount(SongDetailPage, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus }, provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: back } } });
  await flushPromises(); return { request, router, back, store: useTvDetailStore() };
}

describe('Song detail production focus and recovery', () => {
  it('shows actual artwork and a readable duration', async () => {
    await setup(Promise.resolve({ ...song(), artwork: 'https://imge.kugou.com/400/fixture.jpg' }));
    expect(wrapper!.get('.hero-art img').attributes('src')).toContain('/fixture.jpg');
    expect(wrapper!.get('.hero-copy small').text()).toBe('3:20');
  });
  it('does not present missing duration as zero seconds', async () => {
    await setup(Promise.resolve({ ...song(), track: { ...song().track, durationMs: 0 } }));
    expect(wrapper!.get('.hero-copy small').text()).toBe('时长暂不可用');
  });
  it('uses Media3 duration only for the matching song', async () => {
    await setup(Promise.resolve(song())); const player = useTvPlayerStore();
    player.currentTrack = { id: 'other', title: 'Other', artists: [], album: '', durationMs: 0, qualities: [] }; player.durationMs = 241763;
    await flushPromises(); expect(wrapper!.get('.hero-copy small').text()).toBe('3:20');
    player.currentTrack = { ...player.currentTrack, id: 'fixture' }; await flushPromises();
    expect(wrapper!.get('.hero-copy small').text()).toBe('4:01');
  });
  it('focuses a remote-reachable retry on initial error', async () => {
    await setup(); expect((document.activeElement as HTMLElement).dataset.focusId).toBe('song-retry');
    expect(tvFocusRegistry.move('left')).toBe(true); expect(document.activeElement).toBe(nav);
  });
  it('keeps retry mounted while loading and suppresses duplicate confirms', async () => {
    const { request } = await setup(); let resolve!: (value: unknown) => void;
    request.mockImplementation(() => new Promise(done => { resolve = done; })); request.mockClear();
    const button = wrapper!.get('[data-focus-id="song-retry"]'); await button.trigger('click'); await button.trigger('click');
    expect(request).toHaveBeenCalledTimes(1); expect(document.activeElement).toBe(button.element); expect(button.attributes('aria-busy')).toBe('true');
    resolve(song()); await flushPromises(); expect((document.activeElement as HTMLElement).dataset.focusId).toBe('detail-primary');
  });
  it('does not steal focus when a retry resolves after leaving for navigation', async () => {
    const { request } = await setup(); let resolve!: (value: unknown) => void;
    request.mockImplementation(() => new Promise(done => { resolve = done; }));
    await wrapper!.get('[data-focus-id="song-retry"]').trigger('click'); nav!.focus(); resolve(song()); await flushPromises();
    expect(document.activeElement).toBe(nav);
  });
  it('registers Back before the request finishes and never re-registers after unmount', async () => {
    let resolve!: (value: unknown) => void; const { back, router } = await setup(new Promise(done => { resolve = done; }));
    const goBack = vi.spyOn(router, 'back').mockImplementation(() => undefined);
    expect(back.dispatch()).toBe('page'); expect(goBack).toHaveBeenCalledTimes(1);
    wrapper!.unmount(); wrapper = undefined; const register = vi.spyOn(back, 'register');
    resolve(song()); await flushPromises(); expect(register).not.toHaveBeenCalled(); expect(back.dispatch()).toBe('home-exit');
  });
  it('does not send playback commands for metadata without a source', async () => {
    const { request } = await setup(Promise.resolve({ ...song(), track: null })); request.mockClear();
    await wrapper!.get('[data-focus-id="detail-primary"]').trigger('click'); expect(request).not.toHaveBeenCalled();
    expect(wrapper!.text()).toContain('音源');
  });
  it('retains retry focus on another failure', async () => {
    await setup(); const button = wrapper!.get('[data-focus-id="song-retry"]');
    await button.trigger('click'); await flushPromises(); expect(document.activeElement).toBe(button.element);
    expect(button.attributes('aria-busy')).toBe('false');
  });
  it('reloads when the reused song route changes id', async () => {
    const { request, router, store } = await setup(Promise.resolve(song())); request.mockResolvedValue(song('other'));
    await router.push({ name: 'song-detail', params: { id: 'other' } }); await flushPromises();
    expect(store.detail?.id).toBe('other');
  });
});

describe('Shared detail request ownership', () => {
  it('does not attach a previous songs comments to the new detail', async () => {
    const { store, request } = await setup(Promise.resolve(song())); let resolve!: (value: unknown) => void;
    request.mockImplementationOnce(() => new Promise(done => { resolve = done; })).mockResolvedValueOnce(song('new'));
    const oldComments = store.loadComments('song', 'fixture'); await store.loadDetail('song', 'new');
    resolve({ items: [], nextCursor: 'stale', total: 999 }); await oldComments;
    expect(store.comments.nextCursor).toBeNull(); expect(store.commentsStatus).toBe('idle');
  });
  it('clears stale detail while loading another item', async () => {
    const { store, request } = await setup(Promise.resolve(song()));
    request.mockImplementation(() => new Promise(() => undefined)); void store.loadDetail('song', 'other');
    expect(store.detail).toBeNull();
  });
  it('ignores an older success after a newer detail', async () => {
    const { store, request } = await setup(Promise.resolve(song())); let resolve!: (value: unknown) => void;
    request.mockImplementationOnce(() => new Promise(done => { resolve = done; })).mockResolvedValueOnce(song('new'));
    const old = store.loadDetail('song', 'old'); await store.loadDetail('song', 'new'); resolve(song('old')); await old;
    expect(store.detail?.id).toBe('new'); expect(store.status).toBe('ready');
  });
  it('ignores an older failure after a newer detail', async () => {
    const { store, request } = await setup(Promise.resolve(song())); let reject!: (value: unknown) => void;
    request.mockImplementationOnce(() => new Promise((_done, fail) => { reject = fail; })).mockResolvedValueOnce(song('new'));
    const old = store.loadDetail('song', 'old'); await store.loadDetail('song', 'new'); reject(new Error('fixture failure')); await old;
    expect(store.detail?.id).toBe('new'); expect(store.status).toBe('ready');
  });
});
