import { createPinia } from 'pinia';
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { nextTick } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { vTvFocus } from '../focus/directive';
import TvShell from '../layout/TvShell.vue';
import { installNativeClientForTest } from '../native/client';
import type { TvContentCard } from '../native/contentTypes';
import { createTvRouter } from '../router';
import { useTvAppStore } from '../stores/app';
import { detailChildKey, useTvDetailStore } from '../stores/detail';
import { useTvPlayerStore } from '../stores/player';
import { useTvLibraryStore } from '../stores/library';

enableAutoUnmount(afterEach);
const request = vi.fn();
const empty = { items: [], nextCursor: null, total: 0 };
const track = (id: string) => ({ id, title: id, artist: 'Artist', album: '', artwork: '', durationMs: 1000 });
const card = (id: string, kind: TvContentCard['kind'] = 'song'): TvContentCard => ({ id, title: id, subtitle: 'Artist', artwork: '', kind });
function deferred() {
  let resolve!: (value: unknown) => void, reject!: (error: Error) => void;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}
beforeEach(() => {
  localStorage.clear();
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  request.mockReset().mockImplementation(() => Promise.resolve(empty));
  installNativeClientForTest({ request, on: () => () => undefined });
});
afterEach(() => { delete window.EchoTvNative; vi.restoreAllMocks(); });

async function start(path: string) {
  const pinia = createPinia();
  const router = createTvRouter(); await router.push(path); await router.isReady();
  useTvAppStore(pinia).session = { status: 'loggedIn', displayName: 'Pagination test' };
  const player = useTvPlayerStore(pinia);
  player.currentTrack = { ...track('playing'), artists: ['Artist'], qualities: ['high'] };
  const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
  await flushPromises();
  return { wrapper, router, detail: useTvDetailStore(pinia), library: useTvLibraryStore(pinia) };
}
function focus(wrapper: VueWrapper, id: string) {
  const node = wrapper.get<HTMLElement>(`[data-focus-id="${id}"]`);
  node.element.focus(); return node;
}
function press(key: string) { window.dispatchEvent(new KeyboardEvent('keydown', { key })); }
function activeId() { return (document.activeElement as HTMLElement).dataset.focusId; }

describe('paginated pages with the real Shell and player dock', () => {
  it.each(['songs', 'albums', 'mv'])('hands the artist %s final page to its first added item and player UP returns there', async tab => {
    request.mockImplementation(() => Promise.resolve(empty));
    const { wrapper, detail } = await start('/artist/pagination-' + tab);
    request.mockResolvedValueOnce({ id: 'pagination-' + tab, kind: 'artist', title: 'Artist', artwork: '', summary: '',
      tabs: ['songs', 'albums', 'mv'], hotTracks: [track('old')], children: empty });
    await focus(wrapper, 'artist-retry').trigger('click'); await flushPromises();
    detail.children[detailChildKey('artist', 'pagination-' + tab, tab)] = {
      tracks: tab === 'songs' ? [track('old')] : [], items: tab === 'songs' ? [] : [card('old', tab === 'mv' ? 'mv' : 'album')], nextCursor: '2', total: 2,
    };
    detail.childrenStatus[detailChildKey('artist', 'pagination-' + tab, tab)] = 'ready';
    await focus(wrapper, 'artist-tab-' + tab).trigger('click'); await flushPromises();
    const pending = deferred(); request.mockReturnValueOnce(pending.promise);
    const action = focus(wrapper, 'artist-page-action');
    press('ArrowUp'); expect(activeId()).toBe(tab === 'songs' ? 'artist-old' : `artist-${tab}-0`);
    focus(wrapper, 'artist-page-action'); await action.trigger('click');
    expect(document.activeElement).toBe(action.element);
    const surface = wrapper.get<HTMLElement>('.content-surface').element;
    surface.scrollTop = 1800;
    pending.resolve(tab === 'songs' ? { items: [], tracks: [track('new')], nextCursor: null, total: 2 }
      : { items: [card('new', tab === 'mv' ? 'mv' : 'album')], nextCursor: null, total: 2 });
    await flushPromises();
    const newId = tab === 'songs' ? 'artist-new' : `artist-${tab}-1`;
    expect(activeId()).toBe(newId);
    expect(wrapper.find('[data-focus-id="artist-page-action"]').exists()).toBe(false);
    expect(surface.scrollTop).toBe(1800);
    press('ArrowDown'); expect(activeId()).toBe('mini-open');
    press('ArrowUp'); expect(activeId()).toBe(newId); expect(surface.scrollTop).toBe(1800);
  });

  it.each([
    ['cloud', 'cloud-new'], ['history', 'history-new'], ['favorites', 'favorites-songs-new'], ['purchased', 'purchased-songs-new'],
  ])('keeps %s pagination mounted through failure and retries the failed cursor', async (page, newId) => {
    request.mockImplementation((method: string) => Promise.resolve(method === 'library.section'
      ? { items: [card('old')], nextCursor: 'next-2', total: 2 } : empty));
    const { wrapper } = await start('/library/' + page);
    const pending = deferred(); request.mockReturnValueOnce(pending.promise);
    const action = focus(wrapper, 'pagination-load-more'); await action.trigger('click');
    await action.trigger('click');
    expect(document.activeElement).toBe(action.element);
    expect(action.attributes('aria-busy')).toBe('true');
    pending.reject(new Error('offline')); await flushPromises();
    expect(document.activeElement).toBe(action.element);
    expect(action.attributes('data-focus-id')).toBe('pagination-retry');
    const failedCall = request.mock.calls[request.mock.calls.length - 1];
    expect(failedCall[1]).toMatchObject({ cursor: 'next-2' });
    request.mockResolvedValueOnce({ items: [card('new')], nextCursor: null, total: 2 });
    await action.trigger('click'); await flushPromises();
    expect(request).toHaveBeenLastCalledWith(...failedCall);
    expect(activeId()).toBe(newId);
    expect(wrapper.find('[data-focus-id="pagination-load-more"]').exists()).toBe(false);
    press('ArrowUp'); expect(activeId()).toBe(newId.replace('new', 'old'));
  });

  it.each(['album', 'playlist'])('retains %s songs on next-page failure and retries that page', async kind => {
    request.mockImplementation((method: string) => Promise.resolve(method === 'content.detail' ? {
      id: 'test', title: 'Detail', kind, artwork: '', artist: 'Artist', publishDate: '', introduction: '',
      creator: { id: 'u', nickname: 'User', avatar: '', summary: '' }, playCount: 0, tags: [], trackCount: 2,
      tabs: ['songs', 'comments', 'introduction'], tracks: { items: [track('old')], nextCursor: '2', total: 2 },
    } : empty));
    const { wrapper } = await start('/' + kind + '/test');
    const action = focus(wrapper, 'pagination-load-more');
    request.mockRejectedValueOnce(new Error('offline')); await action.trigger('click'); await flushPromises();
    expect(document.activeElement).toBe(action.element);
    request.mockResolvedValueOnce({ items: [track('new')], nextCursor: null, total: 2 });
    await action.trigger('click'); await flushPromises();
    expect(request).toHaveBeenLastCalledWith('content.detailChildren', { kind, id: 'test', tab: 'songs', cursor: '2' });
    expect(activeId()).toBe(kind + '-new');
    expect(wrapper.find(`[data-focus-id="${kind}-old"]`).exists()).toBe(true);
  });

  it('preserves the player selection during a slow history append', async () => {
    request.mockImplementation((method: string) => Promise.resolve(method === 'library.section'
      ? { items: [card('old')], nextCursor: '2', total: 2 } : empty));
    const { wrapper } = await start('/library/history');
    const pending = deferred(); request.mockReturnValueOnce(pending.promise);
    await focus(wrapper, 'pagination-load-more').trigger('click');
    focus(wrapper, 'mini-toggle');
    pending.resolve({ items: [card('new')], nextCursor: null, total: 2 }); await flushPromises();
    expect(activeId()).toBe('mini-toggle');
    await nextTick(); expect(activeId()).toBe('mini-toggle');
  });

  it('player UP uses the newest browse focus when no dock origin exists', async () => {
    request.mockImplementation((method: string) => Promise.resolve(method === 'library.section'
      ? { items: [card('old'), card('new')], nextCursor: null, total: 2 } : empty));
    const { wrapper } = await start('/library/history');
    focus(wrapper, 'history-new'); focus(wrapper, 'mini-toggle');
    press('ArrowUp'); expect(activeId()).toBe('history-new');
  });

  it('player UP finds content near the viewport when the loading action was removed', async () => {
    request.mockImplementation((method: string) => Promise.resolve(method === 'library.section'
      ? { items: [card('old')], nextCursor: '2', total: 3 } : empty));
    const { wrapper } = await start('/library/history');
    const pending = deferred(); request.mockReturnValueOnce(pending.promise);
    await focus(wrapper, 'pagination-load-more').trigger('click');
    press('ArrowDown'); expect(activeId()).toBe('mini-open');
    const surface = wrapper.get<HTMLElement>('.content-surface').element;
    surface.scrollTop = 1600;
    vi.spyOn(surface, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 1000, 800));
    pending.resolve({ items: [card('new'), card('later')], nextCursor: null, total: 3 }); await flushPromises();
    expect(activeId()).toBe('mini-open');
    vi.spyOn(wrapper.get<HTMLElement>('[data-focus-id="history-old"]').element, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, -900, 800, 90));
    vi.spyOn(wrapper.get<HTMLElement>('[data-focus-id="history-new"]').element, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 680, 800, 90));
    vi.spyOn(wrapper.get<HTMLElement>('[data-focus-id="history-later"]').element, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 880, 800, 90));
    press('ArrowUp'); expect(activeId()).toBe('history-new'); expect(surface.scrollTop).toBe(1600);
  });

  it('does not restore old dock scroll after the list targets change', async () => {
    request.mockImplementation((method: string) => Promise.resolve(method === 'library.section'
      ? { items: [card('old')], nextCursor: null, total: 1 } : empty));
    const { wrapper, library } = await start('/library/history');
    const surface = wrapper.get<HTMLElement>('.content-surface').element;
    surface.scrollTop = 500; focus(wrapper, 'history-old');
    press('ArrowDown'); expect(activeId()).toBe('mini-open');
    library.sections.history.items.push(card('new'));
    await nextTick(); surface.scrollTop = 1500;
    press('ArrowUp'); expect(activeId()).toBe('history-old'); expect(surface.scrollTop).toBe(1500);
  });
});
