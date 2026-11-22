import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import type { Component } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { useTvDetailStore } from '../stores/detail';
import { useTvLibraryStore } from '../stores/library';
import { useTvPlayerStore } from '../stores/player';
import { useTvSearchStore } from '../stores/search';
import { canvasScaleForViewport, viewportWithKeyboard } from '../layout/canvasScale';
import { createTvRouter } from '../router';
import { createMemoryHistory } from 'vue-router';
import { navigationSection } from '../router/navigation';
import CommentList from '../content/CommentList.vue';
import DetailHero from '../content/DetailHero.vue';
import PluginPage from './PluginPage.vue';
import QueueDrawer from '../player/QueueDrawer.vue';
import AboutPage from './AboutPage.vue';

const track = { id: 'tv_a', title: 'A', artist: 'Artist', album: '', artwork: '', durationMs: 1000 };
const child = { items: [track], nextCursor: '2', total: 4 };
const detail = (kind: 'album' | 'playlist') => ({ id: '1', kind, title: 'Collection', artwork: '',
  tabs: ['songs', 'comments', 'introduction'], introduction: '', tracks: { items: [], nextCursor: null, total: null },
  ...(kind === 'album' ? { artist: 'Artist', publishDate: '' } : {
    creator: { id: 'u', nickname: 'User', avatar: '', summary: '' }, playCount: 0, tags: [], trackCount: null,
  }),
});
function client(handler: (method: string, payload: unknown) => unknown) {
  const request = vi.fn(async (method: string, payload: unknown) => handler(method, payload));
  installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => {} });
  return request;
}
const mounts: Array<{ unmount(): void }> = [];
function render(component: unknown, props = {}) {
  const wrapper = mount(component as Component, { props, global: { directives: { tvFocus: {} } } });
  mounts.push(wrapper); return wrapper;
}
beforeEach(() => { setActivePinia(createPinia()); window.EchoTvNative = { postMessage() {}, onmessage: null }; });
afterEach(() => { mounts.splice(0).forEach(w => w.unmount()); delete window.EchoTvNative; localStorage.clear(); });

describe('r51 reliability and listening upgrades', () => {
  it('reads bundled licenses through the restricted native bridge', async () => {
    const request = client(() => ({ text: 'GNU GENERAL PUBLIC LICENSE\nBundled text' }));
    const wrapper = render(AboutPage);
    await wrapper.get('[data-focus-id="about-license-gpl"]').trigger('click'); await flushPromises();
    expect(request).toHaveBeenCalledWith('app.readLicense', { kind: 'gpl' });
    expect(wrapper.get('.license-reader').text()).toContain('GNU GENERAL PUBLIC LICENSE');
  });
  it('keeps artist MV cards and their page cursor when returning from a video', async () => {
    client(() => ({ kind: 'artist', id: '1', title: 'Artist', artwork: '', summary: '', tabs: ['songs','albums','mv'], hotTracks: [], children: { items: [], nextCursor: null, total: null } }));
    const store = useTvDetailStore();
    store.children['artist:1:mv'] = { items: [{ id: 'mv:42', kind: 'mv', title: 'Video', artwork: '', subtitle: '' }], tracks: [], nextCursor: '3', total: 80 };
    store.childrenStatus['artist:1:mv'] = 'ready';
    await store.loadDetail('artist', '1');
    expect(store.children['artist:1:mv'].items[0].id).toBe('mv:42');
    expect(store.children['artist:1:mv'].nextCursor).toBe('3');
  });
  it.each(['album', 'playlist'] as const)('retains completed %s songs and pagination across repeated metadata visits', async kind => {
    client(method => method === 'content.detail' ? detail(kind) : child);
    const store = useTvDetailStore();
    await store.loadDetail(kind, '1'); await store.loadChildren(kind, '1', 'songs');
    for (let i = 0; i < 5; i++) {
      await store.loadDetail(kind, '1');
      expect(store.children[`${kind}:1:songs`].tracks.map(t => t.id)).toEqual(['tv_a']);
      expect(store.children[`${kind}:1:songs`].nextCursor).toBe('2');
      expect(store.childrenStatus[`${kind}:1:songs`]).toBe('ready');
    }
  });
  it('does not retain ready when an empty child cache has no successful data', async () => {
    client(() => detail('album'));
    const store = useTvDetailStore();
    store.childrenStatus['album:1:songs'] = 'ready';
    await store.loadDetail('album', '1');
    expect(store.childrenStatus['album:1:songs']).toBeUndefined();
  });
  it('does not describe a failed comment request as no comments', () => {
    const wrapper = render(CommentList, { comments: [], loading: false, hasMore: false, error: true });
    expect(wrapper.text()).not.toContain('还没有评论'); expect(wrapper.text()).toContain('加载失败');
  });
  it('keeps the physical TV scale during an IME height reduction and resets for a real resize', () => {
    const stable = { width: 960, height: 540 };
    const ime = viewportWithKeyboard(960, 300, stable, true);
    expect(ime.keyboard).toBe(true); expect(canvasScaleForViewport(ime.width, ime.height)).toBe(.5);
    expect(viewportWithKeyboard(960, 540, stable, true).keyboard).toBe(false);
    expect(viewportWithKeyboard(1280, 720, stable, true)).toEqual({ width: 1280, height: 720, keyboard: false });
    expect(viewportWithKeyboard(960, 300, stable, false).height).toBe(300);
  });
  it('keeps search ancestry through artist and song details without replacing history', async () => {
    const router = createTvRouter();
    await router.push('/search');
    await router.push({ name: 'artist-detail', params: { id: 'artist:1' }, query: { sourceFocusId: 'search-artist-0' } });
    await router.push({ name: 'song-detail', params: { id: 'tv_a' }, query: { sourceFocusId: 'artist-tv_a' } });
    expect(navigationSection(router.currentRoute.value)).toBe('search');
    router.back(); await flushPromises(); expect(router.currentRoute.value.name).toBe('artist-detail');
    router.back(); await flushPromises(); expect(router.currentRoute.value.name).toBe('search');
  });
  it('enriches an already-written history entry without trapping Back on the detail', async () => {
    const history = createMemoryHistory('/');
    const router = createTvRouter(history);
    await router.push('/home');
    // Model browser hash/pop navigation: the URL exists before guards run.
    history.push('/mv/mv:50?title=Idle-test');
    await router.push('/mv/mv:50?title=Idle-test');
    expect(router.currentRoute.value.query.sourceSection).toBe('home');
    router.back(); await flushPromises();
    expect(router.currentRoute.value.name).toBe('home');
  });
  it('normalizes favorites and uses loaded membership consistently without treating unknown as false', () => {
    const store = useTvDetailStore(); const library = useTvLibraryStore();
    expect(store.favoriteState('song', 'tv_a')).toBeUndefined();
    library.sections.favoriteSongs = { items: [{ id: 'song:tv_a', title: 'A', subtitle: '', artwork: '', kind: 'song' }], status: 'ready', total: 3, nextCursor: '2' };
    expect(store.favoriteState('song', 'tv_a')).toBe(true);
    expect(store.favoriteState('song', 'missing')).toBeUndefined();
    store.favorite['song:tv_a'] = false;
    expect(store.favoriteState('song', 'song:tv_a')).toBe(false);
  });
  it('accepts the actual native plugins response without inventing an entry field', async () => {
    client(() => ({ plugins: [{ id: 'local-tv', version: '1', capabilities: [], bridgeScopes: [] }], disabledReason: null }));
    const wrapper = render(PluginPage); await flushPromises();
    expect(wrapper.text()).toContain('local-tv'); expect(wrapper.text()).not.toContain('加载失败');
  });
  it('shows a disabled extension state instead of a retry error', async () => {
    client(() => ({ plugins: [], disabledReason: 'disabled' }));
    const wrapper = render(PluginPage); await flushPromises();
    expect(wrapper.text()).toContain('当前设备暂不支持'); expect(wrapper.text()).not.toContain('加载失败');
  });
  it('keeps artist biographies compact but provides full text on confirmation', async () => {
    const wrapper = render(DetailHero, { title: 'Artist', subtitle: '', meta: '简介'.repeat(80), primaryLabel: '播放', compact: true });
    expect(wrapper.find('.summary-clamped').exists()).toBe(true);
    await wrapper.get('[data-focus-id="detail-summary"]').trigger('click');
    expect(wrapper.find('.summary-clamped').exists()).toBe(false); expect(wrapper.text()).toContain('收起简介');
  });
  it('does not dispatch play-all for empty tracks', async () => {
    const wrapper = render(DetailHero, { title: 'Album', subtitle: '', meta: '', primaryLabel: '播放全部', primaryDisabled: true });
    await wrapper.get('[data-focus-id="detail-primary"]').trigger('click'); expect(wrapper.emitted('primary')).toBeUndefined();
  });
  it('clears persisted search history', () => {
    localStorage.setItem('tv-search-history', '["old"]');
    const store = useTvSearchStore(); store.clearHistory();
    expect(store.history).toEqual([]); expect(JSON.parse(localStorage.getItem('tv-search-history')!)).toEqual([]);
  });
  it('distinguishes pending buffering error and paused status', async () => {
    const player = useTvPlayerStore();
    expect(player.statusLabel).toBe('已暂停'); player.buffering = true;
    expect(player.statusLabel).toBe('正在缓冲…');
    player.error = { code: 'PLAYBACK_RESOLVE_FAILED', message: 'Failed', retryable: true, errorId: 1 };
    expect(player.statusLabel).toContain('播放失败');
    player.pendingOperations = 1; expect(player.statusLabel).toBe('正在处理…');
  });
  it('sends real native queue edit commands and requires a second clear confirmation', async () => {
    const request = client(() => ({ accepted: true }));
    const track = { id: 'tv_a', title: 'A', artists: [], album: '', durationMs: 1, qualities: [] };
    const wrapper = render(QueueDrawer, { queue: [track, { ...track, id: 'tv_b' }, { ...track, id: 'tv_c' }], queueIndex: 0 });
    await wrapper.get('[data-focus-id="queue-shuffle"]').trigger('click'); await flushPromises();
    expect(request).toHaveBeenCalledWith('player.editQueue', { action: 'shuffle' });
    request.mockClear();
    await wrapper.get('[data-focus-id="queue-clear"]').trigger('click'); expect(request).not.toHaveBeenCalled();
    await wrapper.get('[data-focus-id="queue-clear"]').trigger('click'); await flushPromises();
    expect(request).toHaveBeenCalledWith('player.editQueue', { action: 'clearPending' });
    expect(wrapper.text()).toContain('当前曲目'); expect(wrapper.text()).not.toContain('正在播放');
  });
});
