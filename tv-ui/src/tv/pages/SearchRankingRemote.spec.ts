import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia } from 'pinia';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createTvRouter } from '../router';
import { vTvFocus, tvFocusRegistry } from '../focus/directive';
import { BackLayerRegistry, BACK_LAYER_REGISTRY_KEY } from '../input/backCoordinator';
import { installNativeClientForTest } from '../native/client';
import SearchPage from './SearchPage.vue';
import RankingPage from './RankingPage.vue';

const request = vi.fn();
const song = { id: 'song-31', title: 'Song', kind: 'song', subtitle: 'Artist', artwork: '' };
const searchPage = { category: 'song', items: [song], nextCursor: null, total: 1 };
const explore = { newSongs: [], newAlbums: [], newPlaylists: [], artists: [], rankings: [{ id: 'a', title: 'Chart A', artwork: '' }, { id: 'b', title: 'Chart B', artwork: '' }] };
let wrapper: VueWrapper | undefined;
let nav: HTMLButtonElement;
beforeEach(() => {
  localStorage.clear();
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  request.mockReset();
  installNativeClientForTest({ request, on: () => () => undefined });
  nav = document.createElement('button'); nav.dataset.focusId = 'nav-home'; document.body.append(nav); tvFocusRegistry.register(nav);
});
afterEach(() => {
  wrapper?.unmount(); wrapper = undefined;
  tvFocusRegistry.unregister(nav); nav.remove();
  delete window.EchoTvNative;
  vi.restoreAllMocks();
});
async function start(path = '/search') {
  const router = createTvRouter(); await router.push(path); await router.isReady();
  const layers = new BackLayerRegistry(id => tvFocusRegistry.restore(id));
  wrapper = mount(path === '/search' ? SearchPage : RankingPage, {
    attachTo: document.body,
    global: { plugins: [createPinia(), router], directives: { tvFocus: vTvFocus }, provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: layers } },
  });
  await flushPromises();
  return { router, layers };
}
async function submit() {
  await wrapper!.get('input').setValue('music');
  const button = wrapper!.get<HTMLButtonElement>('[data-focus-id=search-submit]'); button.element.focus();
  await button.trigger('click');
}

describe('search remote focus and ranking lifecycle', () => {
  it('keeps browsing input read-only until Confirm and restores navigation after system IME dismissal', async () => {
    request.mockResolvedValue({ terms: [] });
    await start();
    const input = wrapper!.get<HTMLInputElement>('input');
    expect(input.element.readOnly).toBe(true);
    expect(request).not.toHaveBeenCalledWith('app.showIme', {});
    await input.trigger('keydown', { key: 'Enter' }); await flushPromises();
    expect(input.element.readOnly).toBe(false);
    expect(request).toHaveBeenCalledWith('app.showIme', {});
    window.dispatchEvent(new Event('echo-tv-ime-hidden')); await flushPromises();
    expect(input.element.readOnly).toBe(true);
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('search-submit');
    expect(wrapper!.attributes('data-search-submitted')).toBe('false');
  });

  it('authentication errors focus a login action and keep a retry after QR login', async () => {
    request.mockImplementation(method => method === 'content.search' ? Promise.reject({ code: 'AUTH_EXPIRED' }) : Promise.resolve({ terms: [] }));
    await start(); await submit(); await flushPromises();
    const login = wrapper!.get<HTMLButtonElement>('[data-focus-id=search-login]');
    expect(document.activeElement).toBe(login.element);
    const requested = vi.fn(); window.addEventListener('echo-tv-login-requested', requested, { once: true });
    await login.trigger('click');
    expect(requested).toHaveBeenCalledOnce();
    expect(wrapper!.find('[data-focus-id=search-retry]').exists()).toBe(true);
    expect(wrapper!.text()).not.toContain('没有找到相关内容');
  });

  it('shows song-shaped loading slots then enters the actual first song id', async () => {
    let finish!: (payload: unknown) => void;
    request.mockImplementation(method => method === 'content.search' ? new Promise(resolve => { finish = resolve; }) : Promise.resolve({ terms: [] }));
    await start(); await submit();
    expect(wrapper!.find('[data-skeleton=songs]').exists()).toBe(true);
    expect(wrapper!.text()).not.toContain('没有找到');
    finish(searchPage); await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('search-song-song-31');
    expect(wrapper!.get('[data-focus-id=search-song-song-31]').attributes('data-focus-up')).toBe('search-tab-song');
  });
  it.each(['navigate', 'unmount'] as const)('pending search and late IME events never steal focus after %s', async action => {
    let finish!: (payload: unknown) => void;
    request.mockImplementation(method => method === 'content.search' ? new Promise(resolve => { finish = resolve; }) : Promise.resolve({ terms: [] }));
    await start(); await submit(); nav.focus();
    if (action === 'unmount') { wrapper!.unmount(); wrapper = undefined; }
    const restore = vi.spyOn(tvFocusRegistry, 'restore');
    finish(searchPage); await flushPromises();
    window.dispatchEvent(new Event('echo-tv-ime-hidden')); await flushPromises();
    expect(document.activeElement).toBe(nav); expect(restore).not.toHaveBeenCalled();
  });
  it('first-page failure has a remote retry and successful retry focuses the result', async () => {
    let calls = 0;
    request.mockImplementation(method => method === 'content.search'
      ? ++calls === 1 ? Promise.reject(new Error('offline')) : Promise.resolve(searchPage)
      : Promise.resolve({ terms: [] }));
    await start(); await submit(); await flushPromises();
    const retry = wrapper!.get<HTMLButtonElement>('[data-focus-id=search-retry]');
    expect(document.activeElement).toBe(retry.element); await retry.trigger('click'); await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('search-song-song-31');
  });
  it('category selection retains its tab and Down targets the returned category', async () => {
    request.mockImplementation((method, payload) => method === 'content.search'
      ? Promise.resolve(payload.category === 'album' ? { category: 'album', items: [{ ...song, id: 'album:1', kind: 'album' }], nextCursor: null, total: 1 } : searchPage)
      : Promise.resolve({ terms: [] }));
    await start(); await submit(); await flushPromises();
    const tab = wrapper!.get<HTMLButtonElement>('[data-focus-id=search-tab-album]'); tab.element.focus(); await tab.trigger('click'); await flushPromises();
    expect(document.activeElement).toBe(tab.element); expect(tab.attributes('data-focus-down')).toBe('search-album-0');
  });
  it('Back dismisses IME first, then returns results to suggestions without leaving search', async () => {
    request.mockImplementation(method => Promise.resolve(method === 'content.search' ? searchPage : { terms: [] }));
    const { layers, router } = await start(); await submit(); await flushPromises();
    const input = wrapper!.get<HTMLInputElement>('input'); input.element.focus(); await input.trigger('keydown', { key: 'Enter' });
    expect(layers.dispatch()).toBe('ime'); await flushPromises();
    expect(wrapper!.attributes('data-search-submitted')).toBe('true');
    expect(layers.dispatch()).toBe('page'); await flushPromises();
    expect(wrapper!.attributes('data-search-submitted')).toBe('false');
    expect(router.currentRoute.value.name).toBe('search'); expect(input.element.value).toBe('music');
    expect(document.activeElement).toBe(input.element);
  });
  it('same-component chart id changes reload tracks and title, and Back works while loading', async () => {
    let finish!: (payload: unknown) => void;
    request.mockImplementation(method => method === 'content.ranking' ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(explore));
    const { router, layers } = await start('/ranking/a');
    expect(wrapper!.find('[data-skeleton=songs]').exists()).toBe(true);
    finish({ items: [{ id: 'a-song', title: 'A', artist: 'Artist', album: '', artwork: '', durationMs: 1000 }], nextCursor: null, total: 1 }); await flushPromises();
    await router.push('/ranking/b'); await flushPromises();
    expect(wrapper!.find('[data-focus-id=ranking-a-song]').exists()).toBe(false);
    expect(wrapper!.find('[data-skeleton=songs]').exists()).toBe(true);
    expect(request).toHaveBeenCalledWith('content.ranking', { id: 'b' });
    expect(layers.dispatch()).toBe('page'); await flushPromises();
    expect(router.currentRoute.value.name).toBe('ranking');
    finish({ items: [], total: 0, nextCursor: null }); await flushPromises();
    expect(wrapper!.findAll('[data-focus-id^=ranking-list-]')).toHaveLength(2);
  });
});
