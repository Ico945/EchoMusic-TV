import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { nextTick, type Component } from 'vue';
import { createTvRouter } from '../router';
import { installNativeClientForTest } from '../native/client';
import { tvFocusRegistry, vTvFocus } from '../focus/directive';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import { useTvAppStore, type TvTrack } from '../stores/app';
import { useTvDetailStore } from '../stores/detail';
import { useTvLibraryStore } from '../stores/library';
import { useTvPlayerStore } from '../stores/player';
import { useTvBrowseStore } from '../stores/browse';
import { useTvSearchStore } from '../stores/search';
import { useTvSettingsStore } from '../stores/settings';
import SongActions from '../content/SongActions.vue';
import SongList from '../content/SongList.vue';
import SearchPage from './SearchPage.vue';
import RankingPage from './RankingPage.vue';
import PrivateFmPage from './PrivateFmPage.vue';
import CloudPage from './CloudPage.vue';
import PurchasedPage from './PurchasedPage.vue';
import SoundEffectDrawer from '../player/SoundEffectDrawer.vue';
import NowPlayingPage from './NowPlayingPage.vue';
import TvApp from '../TvApp.vue';
import { scrollReadingPanel } from '../content/reading';

const track = (id: string): TvTrack => ({ id, title: `歌曲 ${id}`, artist: '测试歌手', album: '', artwork: '', durationMs: 180000 });
const tracks = ['a', 'b', 'c'].map(track);
const nativeTrack = (id: string) => ({ id, title: `歌曲 ${id}`, artists: ['测试歌手'], album: '', artworkUrl: '', durationMs: 180000, qualities: ['128', '320'] });
const request = vi.fn();
let wrapper: VueWrapper | undefined;
let pinia: ReturnType<typeof createPinia>;
let source: HTMLButtonElement;
let layers: BackLayerRegistry;
beforeEach(() => {
  pinia = createPinia(); setActivePinia(pinia);
  window.EchoTvNative = { postMessage() {}, onmessage: null };
  request.mockReset();
  request.mockImplementation((method: string, payload: Record<string, unknown>) => Promise.resolve(
    method === 'library.favorite' ? { kind: payload.kind, id: payload.id, favorite: payload.favorite }
      : method === 'content.privateFm' ? { items: tracks }
      : method === 'library.section' ? { section: payload.section, items: [], total: 0, nextCursor: null }
      : method === 'content.hotSearch' ? { terms: [] }
      : method === 'share.create' ? { link: 'https://www.kugou.com/test', qrImageDataUrl: 'data:image/png;base64,fixture', clipboardAvailable: false }
      : { accepted: true },
  ));
  installNativeClientForTest({ request, on: () => () => undefined });
  source = document.createElement('button'); source.dataset.focusId = 'review-source'; document.body.append(source);
  tvFocusRegistry.register(source); source.focus();
  layers = new BackLayerRegistry(id => { void nextTick(() => tvFocusRegistry.restore(id)); });
});
afterEach(() => {
  wrapper?.unmount(); wrapper = undefined;
  tvFocusRegistry.unregister(source); source.remove();
  delete window.EchoTvNative;
  vi.useRealTimers(); vi.restoreAllMocks();
});
async function start(component: Component, path = '/search', props: Record<string, unknown> = {}) {
  const router = createTvRouter(); await router.push(path); await router.isReady();
  wrapper = mount(component, { attachTo: document.body, props, global: {
    plugins: [pinia, router], directives: { tvFocus: vTvFocus },
    provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: layers },
  } });
  await flushPromises(); return router;
}
async function menu(favorite = false) {
  return start(SongActions, '/search', { track: tracks[0], sourceFocusId: 'review-source', favorite, onClose: () => { void wrapper!.setProps({ track: null }); } });
}

describe('r45 action semantics and modal focus', () => {
  it.each(['detail', 'library'] as const)('uses the %s favorite state when the caller omits the Boolean prop', async store => {
    if (store === 'detail') useTvDetailStore().favorite['song:a'] = true;
    else useTvLibraryStore().sections.favoriteSongs = { items: [{ kind: 'song', id: 'a', title: '歌曲 a', subtitle: '', artwork: '' }], total: 1, nextCursor: null, status: 'ready' };
    await start(SongActions, '/search', { track: tracks[0], sourceFocusId: 'review-source' });
    expect(wrapper!.get('[data-focus-id=song-menu-favorite]').text()).toBe('取消收藏');
  });
  it('restores the source after Back closes the menu DOM', async () => {
    await menu(); layers.dispatch(); await flushPromises();
    expect(wrapper!.find('[aria-modal=true]').exists()).toBe(false);
    expect(document.activeElement).toBe(source);
  });
  it('returns from the nested share dialog to the original song row', async () => {
    await menu(); await wrapper!.get('[data-focus-id=song-menu-share]').trigger('click'); await flushPromises();
    layers.dispatch(); await flushPromises();
    expect(wrapper!.find('[aria-modal=true]').exists()).toBe(false);
    expect(document.activeElement).toBe(source);
  });
  it('requires explicit confirmation to remove a favorite and starts on keep', async () => {
    await menu(true);
    expect(wrapper!.get('[data-focus-id=song-menu-favorite]').text()).toBe('取消收藏');
    await wrapper!.get('[data-focus-id=song-menu-favorite]').trigger('click');
    expect(document.activeElement?.getAttribute('data-focus-id')).toBe('song-menu-cancel-remove');
    expect(request).not.toHaveBeenCalled();
    await wrapper!.get('[data-focus-id=song-menu-confirm-remove]').trigger('click'); await flushPromises();
    expect(request).toHaveBeenCalledWith('library.favorite', expect.objectContaining({ id: 'a', favorite: false }));
    expect(wrapper!.text()).toContain('已取消收藏');
    expect(wrapper!.find('[aria-modal=true]').exists()).toBe(false);
    expect(document.activeElement).toBe(source);
  });
  it('Back first leaves the removal confirmation without mutating favorites', async () => {
    await menu(true); await wrapper!.get('[data-focus-id=song-menu-favorite]').trigger('click');
    layers.dispatch(); await flushPromises();
    expect(document.activeElement?.getAttribute('data-focus-id')).toBe('song-menu-favorite');
    expect(request).not.toHaveBeenCalled();
  });
  it('adds a favorite and only reports the confirmed state', async () => {
    await menu(); await wrapper!.get('[data-focus-id=song-menu-favorite]').trigger('click'); await flushPromises();
    expect(useTvDetailStore().favorite['song:a']).toBe(true);
    expect(wrapper!.text()).toContain('已收藏');
    expect(wrapper!.text()).not.toContain('已取消收藏');
  });
  it('rejects mismatched native favorite responses rather than showing a false success', async () => {
    request.mockResolvedValue({ kind: 'song', id: 'a', favorite: false });
    await menu(); await wrapper!.get('[data-focus-id=song-menu-favorite]').trigger('click'); await flushPromises();
    expect(wrapper!.text()).toContain('操作未成功');
    expect(wrapper!.text()).not.toContain('已收藏');
    expect(wrapper!.find('[aria-modal=true]').exists()).toBe(true);
  });
  it('does not expose an unimplemented add-to-playlist action', async () => {
    await menu(); expect(wrapper!.find('[data-focus-id=song-menu-add-to-playlist]').exists()).toBe(false);
  });
  it('coalesces repeated next-track confirmation while awaiting native acceptance', async () => {
    let resolve!: (value: unknown) => void;
    request.mockImplementation(() => new Promise(done => { resolve = done; }));
    await menu(); const button = wrapper!.get('[data-focus-id=song-menu-play-next]');
    await button.trigger('click'); await button.trigger('click');
    expect(request).toHaveBeenCalledTimes(1);
    resolve({ accepted: true }); await flushPromises();
    expect(wrapper!.text()).toContain('已加入下一首');
  });
  it('opens song details with the source focus, and releases its Back layer', async () => {
    const router = await menu(); await wrapper!.get('[data-focus-id=song-menu-detail]').trigger('click'); await flushPromises();
    expect(router.currentRoute.value.name).toBe('song-detail');
    expect(router.currentRoute.value.query.sourceFocusId).toBe('review-source');
    expect(layers.hasAny(['dialog'])).toBe(false);
  });
  it('opens the actual share dialog and releases all layers on unmount', async () => {
    await menu(); await wrapper!.get('[data-focus-id=song-menu-share]').trigger('click'); await flushPromises();
    expect(request).toHaveBeenCalledWith('share.create', { kind: 'song', id: 'a' });
    expect(wrapper!.find('[data-share-dialog]').exists()).toBe(true);
    wrapper!.unmount(); wrapper = undefined;
    expect(layers.hasAny(['dialog'])).toBe(false);
  });
  it('never restores a background target while a modal is open', async () => {
    await menu(); tvFocusRegistry.restore('review-source');
    expect(wrapper!.get('[aria-modal=true]').element.contains(document.activeElement)).toBe(true);
    source.focus(); expect(tvFocusRegistry.containModalFocus()).toBe(true);
    expect(wrapper!.get('[aria-modal=true]').element.contains(document.activeElement)).toBe(true);
  });
  it('keeps all four directions inside a menu even with background candidates', async () => {
    await menu();
    source.getBoundingClientRect = () => new DOMRect(0, 50, 100, 60);
    wrapper!.findAll<HTMLElement>('[data-focus-id]').forEach((node, i) => { node.element.getBoundingClientRect = () => new DOMRect(500, 50 + i * 80, 300, 60); });
    for (const direction of ['left', 'up', 'right', 'down'] as const) {
      tvFocusRegistry.restore('song-menu-favorite'); tvFocusRegistry.move(direction);
      expect(wrapper!.get('[aria-modal=true]').element.contains(document.activeElement)).toBe(true);
    }
  });
  it.each(['search', 'ranking'] as const)('%s connects its song menu to next-track playback', async page => {
    if (page === 'search') useTvSearchStore().$patch({ submitted: true, query: '测试', results: { items: [{ ...tracks[0], kind: 'song', subtitle: '歌手' }], total: 1, nextCursor: null } });
    else useTvBrowseStore().$patch({ loaded: true, rankingId: 'chart', rankingStatus: 'ready', rankingTracks: tracks });
    await start(page === 'search' ? SearchPage : RankingPage, page === 'search' ? '/search' : '/ranking/chart');
    wrapper!.getComponent(SongList).vm.$emit('menu', tracks[0]); await flushPromises();
    await wrapper!.get('[data-focus-id=song-menu-play-next]').trigger('click'); await flushPromises();
    expect(request).toHaveBeenCalledWith('player.playNext', { trackId: 'a' });
  });
});

describe('r45 FM and recovery', () => {
  it('selects the requested upcoming song in a real multi-track queue', async () => {
    await start(PrivateFmPage, '/fm');
    await wrapper!.get('[data-focus-id=fm-upcoming-c]').trigger('click'); await flushPromises();
    expect(request).toHaveBeenCalledWith('player.playQueue', { trackIds: ['a', 'b', 'c'], startIndex: 2 });
  });
  it('likes the actual current recommendation without changing playback or advancing the display', async () => {
    useTvPlayerStore().currentTrack = nativeTrack('a');
    await start(PrivateFmPage, '/fm'); await wrapper!.get('[data-focus-id=fm-like]').trigger('click'); await flushPromises();
    expect(request).toHaveBeenCalledWith('library.favorite', expect.objectContaining({ id: 'a', favorite: true }));
    expect(request.mock.calls.filter(([method]) => String(method).startsWith('player.'))).toEqual([]);
    expect(wrapper!.get('.now-copy h2').text()).toBe('歌曲 a');
  });
  it('follows native track changes instead of falsely labeling a different recommendation playing', async () => {
    await start(PrivateFmPage, '/fm'); expect(wrapper!.get('.now-copy small').text()).toBe('为你推荐');
    const player = useTvPlayerStore(); player.currentTrack = nativeTrack('b'); player.isPlaying = true; await flushPromises();
    expect(wrapper!.get('.now-copy h2').text()).toBe('歌曲 b');
    expect(wrapper!.get('.now-copy small').text()).toBe('正在播放');
  });
  it.each([['cloud', CloudPage], ['purchased', PurchasedPage]] as const)('loads %s after QR login without leaving the page', async (name, component) => {
    await start(component, `/library/${name}`);
    expect(request.mock.calls.filter(([method]) => method === 'library.section')).toHaveLength(0);
    useTvAppStore().session = { status: 'loggedIn', displayName: 'fixture' }; await flushPromises();
    expect(request).toHaveBeenCalledWith('library.section', { section: name === 'cloud' ? 'cloud' : 'purchasedSongs' });
  });
  it('focuses cancel on cloud deletion and returns without a delete request', async () => {
    useTvAppStore().session = { status: 'loggedIn', displayName: 'fixture' };
    useTvLibraryStore().sections.cloud = { items: [{ kind: 'song', id: 'a', title: 'fixture', subtitle: '', artwork: '' }], total: 1, nextCursor: null, status: 'ready' };
    await start(CloudPage, '/library/cloud'); const button = wrapper!.get<HTMLElement>('[data-focus-id=cloud-a]'); button.element.focus();
    await button.trigger('click'); await flushPromises();
    expect(document.activeElement?.getAttribute('data-focus-id')).toBe('cancel-delete');
    await wrapper!.get('[data-focus-id=cancel-delete]').trigger('click'); await flushPromises();
    expect(document.activeElement).toBe(button.element);
    expect(request.mock.calls.some(([method]) => method === 'library.deleteCloudItem')).toBe(false);
  });
  it('keeps failed sound-effect choices focusable and offers a retry', async () => {
    request.mockRejectedValue(new Error('offline')); await start(SoundEffectDrawer);
    const button = wrapper!.get<HTMLButtonElement>('[data-focus-id=sound-effect-bass]'); button.element.focus();
    await button.trigger('click'); await flushPromises();
    expect(button.element.disabled).toBe(false); expect(document.activeElement).toBe(button.element);
    expect(wrapper!.text()).toContain('音效未应用');
  });
  it('applies typography and reduced-motion settings to the application canvas', async () => {
    const settings = useTvSettingsStore(); settings.loaded = true;
    await start(TvApp, '/more'); settings.settings.lyricFontScale = 140; settings.settings.reducedMotion = true; await flushPromises();
    expect(wrapper!.get('.tv-canvas').classes()).toContain('tv-reduced-motion');
    expect(wrapper!.get<HTMLElement>('.tv-canvas').element.style.getPropertyValue('--tv-lyric-scale')).toBe('1.4');
  });
  it('applies the selected lyric background on the actual player', async () => {
    await start(NowPlayingPage, '/now-playing'); useTvSettingsStore().settings.lyricBackground = 'gradient'; await flushPromises();
    expect(wrapper!.attributes('data-background')).toBe('gradient');
  });
  it('scrolls long text internally and yields to navigation at its boundary', () => {
    const panel = document.createElement('article');
    Object.defineProperties(panel, { scrollHeight: { value: 600 }, clientHeight: { value: 200 } });
    panel.addEventListener('keydown', scrollReadingPanel);
    const down = () => { const event = new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true }); panel.dispatchEvent(event); return event; };
    expect(down().defaultPrevented).toBe(true); expect(panel.scrollTop).toBe(120);
    panel.scrollTop = 400; expect(down().defaultPrevented).toBe(false);
  });
});
