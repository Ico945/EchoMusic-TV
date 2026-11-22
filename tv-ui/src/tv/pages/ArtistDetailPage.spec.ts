import { createPinia, setActivePinia } from 'pinia';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { tvFocusRegistry, vTvFocus } from '../focus/directive';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { createTvRouter } from '../router';
import { useTvDetailStore } from '../stores/detail';
import { useTvPlayerStore } from '../stores/player';
import ArtistDetailPage from './ArtistDetailPage.vue';

const track = { id: 'tv_hot', title: '热门歌曲', artist: '歌手', album: '', artwork: '', durationMs: 180000 };
const card = { id: 'album:10', kind: 'album', title: '原生专辑', subtitle: '歌手', artwork: '' };
const artist = (id = '1') => ({ id, kind: 'artist', title: `歌手 ${id}`, artwork: '', summary: '',
  tabs: ['songs', 'albums', 'mv'], hotTracks: [track], children: { items: [card], nextCursor: null, total: 1 } });
const emptyPage = { items: [], nextCursor: null, total: 0 };
function deferred() {
  let resolve!: (value: unknown) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<unknown>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}
let wrapper: VueWrapper | undefined;
const controls: HTMLButtonElement[] = [];
afterEach(() => {
  wrapper?.unmount(); wrapper = undefined;
  controls.splice(0).forEach(node => { tvFocusRegistry.unregister(node); node.remove(); });
  delete window.EchoTvNative;
  vi.restoreAllMocks();
});
function control(id: string) {
  const node = document.createElement('button'); node.dataset.focusId = id;
  document.body.append(node); tvFocusRegistry.register(node); controls.push(node); return node;
}
async function setup(payload: unknown = artist(), routeId = 'artist:1') {
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  const request = vi.fn((method: string) => method === 'content.detail' ? Promise.resolve(payload) : Promise.resolve(emptyPage));
  installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });
  const pinia = createPinia(); setActivePinia(pinia);
  const router = createTvRouter();
  await router.push({ name: 'artist-detail', params: { id: routeId }, query: { sourceFocusId: 'source-card' } });
  await router.isReady();
  const nav = control('nav-home'); nav.focus();
  const back = new BackLayerRegistry(() => undefined);
  wrapper = mount(ArtistDetailPage, { attachTo: document.body, global: {
    plugins: [pinia, router], directives: { tvFocus: vTvFocus }, provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: back },
  } });
  await flushPromises();
  return { request, router, back, nav, store: useTvDetailStore(pinia), player: useTvPlayerStore(pinia) };
}
async function selectTab(tab: string) {
  const button = wrapper!.get(`[data-focus-id="artist-tab-${tab}"]`);
  (button.element as HTMLElement).focus(); await button.trigger('click'); await flushPromises();
}

describe('Artist detail native data and remote recovery', () => {
  it.each([false, true])('hands a focused first-page loading placeholder to new MV content without stealing newer focus (%s)', async movedAway => {
    const { request, nav } = await setup();
    const pending = deferred(); request.mockReturnValueOnce(pending.promise);
    await selectTab('mv');
    const loading = wrapper!.get('[data-focus-id="artist-page-action"]');
    (loading.element as HTMLElement).focus(); await flushPromises();
    if (movedAway) nav.focus();
    pending.resolve({ items: [{ ...card, kind: 'mv', id: 'mv:50' }], nextCursor: '2', total: 30 });
    await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe(movedAway ? 'nav-home' : 'artist-mv-0');
  });
  it('loads empty metadata songs independently and retries without showing a false empty state', async () => {
    const metadata = deferred();
    const { request, player } = await setup(metadata.promise);
    request.mockRejectedValueOnce(new Error('offline'));
    metadata.resolve({ ...artist(), hotTracks: [] }); await flushPromises();
    expect(wrapper!.text()).not.toContain('暂无歌曲');
    expect(wrapper!.find('[data-focus-id="detail-primary"]').exists()).toBe(false);
    const retry = wrapper!.get('[data-focus-id="artist-page-action"]');
    expect(retry.text()).toContain('重试');
    const pending = deferred(); request.mockReturnValueOnce(pending.promise);
    (retry.element as HTMLElement).focus(); await retry.trigger('click'); await retry.trigger('click');
    expect(request.mock.calls.filter(([method]) => method === 'content.detailChildren')).toHaveLength(2);
    pending.resolve({ items: [{ ...card, kind:'song' }], tracks:[track],nextCursor:'2',total:2 }); await flushPromises();
    expect(wrapper!.text()).toContain('热门歌曲');
    const play = vi.spyOn(player, 'playQueue').mockResolvedValue();
    await wrapper!.get('[data-focus-id="detail-primary"]').trigger('click'); expect(play).toHaveBeenCalledWith(['tv_hot'],0);
    request.mockResolvedValueOnce({items:[],tracks:[track,{ ...track,id:'tv_second',title:'第二首' }],nextCursor:null,total:2});
    await retry.trigger('click'); await flushPromises();
    expect(request).toHaveBeenLastCalledWith('content.detailChildren',{kind:'artist',id:'artist:1',tab:'songs',cursor:'2'});
    expect(wrapper!.findAll('[data-focus-group=artist]')).toHaveLength(2);
    expect(wrapper!.text()).toContain('已全部加载');
  });

  it('only shows no songs after a successful empty artist song response', async () => {
    const { request } = await setup({ ...artist(), hotTracks: [] });
    expect(request).toHaveBeenLastCalledWith('content.detailChildren',{kind:'artist',id:'artist:1',tab:'songs'});
    expect(wrapper!.text()).toContain('暂无歌曲');
    expect(wrapper!.find('[data-focus-id="detail-primary"]').exists()).toBe(false);
    expect(wrapper!.find('[data-focus-id="detail-share"]').exists()).toBe(false);
  });
  it.each(['1', 'artist:1'])('renders canonical artist songs and albums for payload id %s', async id => {
    const { player, request } = await setup(artist(id));
    expect(wrapper!.text()).toContain('热门歌曲');
    const play = vi.spyOn(player, 'playQueue').mockResolvedValue();
    await wrapper!.get('[data-focus-id="detail-primary"]').trigger('click');
    expect(play).toHaveBeenCalledWith(['tv_hot'], 0);
    await selectTab('albums'); expect(wrapper!.text()).toContain('原生专辑');
    expect(request).not.toHaveBeenCalledWith('content.detailChildren', expect.anything());
  });

  it('registers Back during loading and does not register or focus after unmount', async () => {
    const pending = deferred(); const { back, router } = await setup(pending.promise);
    const goBack = vi.spyOn(router, 'back').mockImplementation(() => undefined);
    expect(back.dispatch()).toBe('page'); expect(goBack).toHaveBeenCalledOnce();
    wrapper!.unmount(); wrapper = undefined;
    const chosen = control('mini-toggle'); chosen.focus();
    const restore = vi.spyOn(tvFocusRegistry, 'restore'); const register = vi.spyOn(back, 'register');
    pending.resolve(artist()); await flushPromises();
    expect(register).not.toHaveBeenCalled(); expect(restore).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(chosen); expect(back.dispatch()).toBe('home-exit');
  });

  it('keeps an enabled loading/retry control and suppresses duplicate requests', async () => {
    const pending = deferred(); const { request } = await setup(pending.promise);
    const button = wrapper!.get('[data-focus-id="artist-retry"]');
    expect(document.activeElement).toBe(button.element); expect(button.attributes('aria-busy')).toBe('true');
    expect(button.attributes('disabled')).toBeUndefined();
    await button.trigger('click'); await button.trigger('click'); expect(request).toHaveBeenCalledTimes(1);
    pending.reject(new Error('offline')); await flushPromises();
    expect(document.activeElement).toBe(button.element); expect(button.text()).toBe('重新加载');
    expect(tvFocusRegistry.move('left')).toBe(true);
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('nav-home');
    (button.element as HTMLElement).focus(); request.mockResolvedValue(artist());
    await button.trigger('click'); await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('detail-primary');
  });

  it.each(['nav-home', 'mini-toggle', 'login-cancel'])('does not steal %s when initial data arrives', async id => {
    const pending = deferred(); const { nav } = await setup(pending.promise);
    const chosen = id === 'nav-home' ? nav : control(id); chosen.focus();
    pending.resolve(artist()); await flushPromises(); expect(document.activeElement).toBe(chosen);
  });

  it('does not jump back to a slow tab after the user selects another one', async () => {
    const { request } = await setup(); const pending = deferred(); request.mockReturnValue(pending.promise);
    await selectTab('mv'); await selectTab('songs');
    pending.resolve(emptyPage); await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('artist-tab-songs');
  });

  it('opens the video identity with its title, artist and exact return focus', async () => {
    const { request, router } = await setup(artist('video-meta'), 'artist:video-meta');
    request.mockResolvedValueOnce({ items: [{ id: 'mv:5101', kind: 'mv', title: '现场 MV', subtitle: '测试歌手', artwork: '' }], nextCursor: null, total: 1 });
    await selectTab('mv');
    const push = vi.spyOn(router, 'push').mockResolvedValue();
    const video = wrapper!.get('[data-focus-id="artist-mv-0"]');
    (video.element as HTMLElement).focus();
    await video.trigger('click');
    expect(push).toHaveBeenCalledWith({ name: 'mv-detail', params: { id: 'mv:5101' },
      query: { sourceFocusId: 'artist-mv-0', title: '现场 MV', artist: '测试歌手' } });
  });

  it.each([false, true])('restores a returned video only after its cards load without stealing newer navigation: %s', async movedAway => {
    const artistId = movedAway ? 'return-moved' : 'return-video';
    const videoPage = { items: [{ id: 'mv:5102', kind: 'mv', title: '返回目标', subtitle: '歌手', artwork: '' }], nextCursor: null, total: 1 };
    const first = await setup(artist(artistId), 'artist:' + artistId);
    first.request.mockResolvedValueOnce(videoPage);
    await selectTab('mv');
    vi.spyOn(first.router, 'push').mockResolvedValue();
    const card = wrapper!.get('[data-focus-id="artist-mv-0"]');
    (card.element as HTMLElement).focus(); await card.trigger('click');
    wrapper!.unmount(); wrapper = undefined; vi.restoreAllMocks();
    const meta = deferred(), children = deferred();
    const returned = await setup(meta.promise, 'artist:' + artistId);
    returned.request.mockReturnValueOnce(children.promise);
    meta.resolve(artist(artistId)); await flushPromises();
    expect(wrapper!.get('[data-focus-id="artist-tab-mv"]').attributes('aria-selected')).toBe('true');
    if (movedAway) returned.nav.focus();
    children.resolve(videoPage); await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe(movedAway ? 'nav-home' : 'artist-mv-0');
  });

  it('can retry the first MV page without a cursor and keeps focus while it loads', async () => {
    const { request } = await setup(); request.mockRejectedValueOnce(new Error('offline'));
    await selectTab('mv'); const button = wrapper!.get('[data-focus-id="artist-page-action"]');
    expect(button.text()).toContain('重试'); (button.element as HTMLElement).focus();
    const pending = deferred(); request.mockReturnValue(pending.promise); request.mockClear();
    await button.trigger('click'); await button.trigger('click');
    expect(request).toHaveBeenCalledExactlyOnceWith('content.detailChildren', { kind: 'artist', id: 'artist:1', tab: 'mv' });
    expect(document.activeElement).toBe(button.element); expect(button.attributes('aria-busy')).toBe('true');
    pending.resolve(emptyPage); await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('artist-tab-mv');
    expect(wrapper!.text()).toContain('暂无MV');
  });

  it('links the active tab directly to its empty-state retry and back', async () => {
    const { request } = await setup(); request.mockRejectedValueOnce(new Error('offline'));
    await selectTab('mv');
    expect(tvFocusRegistry.move('down')).toBe(true);
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('artist-page-action');
    expect(tvFocusRegistry.move('up')).toBe(true);
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('artist-tab-mv');
  });

  it('retains existing albums and retries the failed next cursor', async () => {
    const { request } = await setup({ ...artist(), children: { items: [card], nextCursor: 'next-2', total: 2 } });
    await selectTab('albums'); request.mockRejectedValueOnce(new Error('offline'));
    const button = wrapper!.get('[data-focus-id="artist-page-action"]'); (button.element as HTMLElement).focus();
    await button.trigger('click'); await flushPromises(); expect(wrapper!.text()).toContain('原生专辑');
    request.mockResolvedValue({ items: [{ ...card, id: 'album:11', title: '下一张' }], nextCursor: null, total: 2 });
    await button.trigger('click'); await flushPromises();
    expect(request).toHaveBeenLastCalledWith('content.detailChildren', { kind: 'artist', id: 'artist:1', tab: 'albums', cursor: 'next-2' });
    expect(wrapper!.text()).toContain('原生专辑'); expect(wrapper!.text()).toContain('下一张');
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('artist-albums-1');
  });

  it('resets the tab and uses the new artist favorite key when the route is reused', async () => {
    const { request, router, store } = await setup(); await selectTab('albums');
    store.favorite['artist:2'] = true;
    const favorite = vi.spyOn(store, 'toggleFavorite').mockResolvedValue(true); request.mockResolvedValue(artist('2'));
    await router.push({ name: 'artist-detail', params: { id: 'artist:2' } }); await flushPromises();
    expect(wrapper!.get('[data-focus-id="artist-tab-songs"]').attributes('aria-selected')).toBe('true');
    await wrapper!.get('[data-focus-id="detail-favorite"]').trigger('click');
    expect(favorite).toHaveBeenCalledWith('artist', 'artist:2', false);
  });

  it('does not mark the new route ready when the older detail request completes', async () => {
    const old = deferred(); const current = deferred(); const { request, router } = await setup(old.promise);
    request.mockReturnValue(current.promise);
    await router.push({ name: 'artist-detail', params: { id: 'artist:2' } }); await flushPromises();
    old.resolve(artist()); await flushPromises();
    expect(wrapper!.get('[data-focus-id="artist-retry"]').attributes('aria-busy')).toBe('true');
    current.resolve(artist('2')); await flushPromises(); expect(wrapper!.text()).toContain('歌手 2');
  });
});
