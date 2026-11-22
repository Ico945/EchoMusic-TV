import { createPinia } from 'pinia';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installNativeClientForTest } from '../native/client';
import { createTvRouter } from '../router';
import { vTvFocus } from '../focus/directive';
import { useTvContentStore } from '../stores/content';
import { useTvPlayerStore } from '../stores/player';
import TvShell from './TvShell.vue';

let wrapper: VueWrapper | undefined;
const request = vi.fn();
const track = { id: 'chrome-song', title: '遥控器验收', artists: ['示例歌手'], album: '', durationMs: 180000, qualities: ['128'] };
const playing = { currentTrack: track, isPlaying: false, positionMs: 30000, durationMs: 180000, queue: [track], queueIndex: 0, repeatMode: 'off', quality: '128', lyrics: [], error: null, errorRevision: 0 } as const;
beforeEach(() => {
  localStorage.clear(); request.mockReset();
  request.mockImplementation(method => Promise.resolve(method === 'content.hotSearch' ? { terms: ['音乐', '轻音乐'] } : { accepted: true }));
  installNativeClientForTest({ request, on: () => () => undefined });
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
});
afterEach(() => { wrapper?.unmount(); wrapper = undefined; delete window.EchoTvNative; vi.restoreAllMocks(); });
async function start(path = '/home', withTrack = true) {
  const router = createTvRouter(); await router.push(path); await router.isReady();
  const pinia = createPinia();
  await useTvContentStore(pinia).loadHome({ greeting: '晚上好', hero: { id: track.id, title: track.title, artist: '示例歌手', album: '', artwork: '', durationMs: 180000 }, secondary: [], shelves: [] });
  const player = useTvPlayerStore(pinia); if (withTrack) player.applySnapshot(playing);
  wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
  await flushPromises(); return { router, player };
}
function focus(id: string) { wrapper!.get<HTMLElement>(`[data-focus-id="${id}"]`).element.focus(); }
function active() { return (document.activeElement as HTMLElement).dataset.focusId; }
async function key(name: string, repeat = false) {
  document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: name, repeat, bubbles: true, cancelable: true }));
  await flushPromises();
}

describe('horizontal TV navigation and always-reachable playback dock', () => {
  it('keeps playback failures inside the reserved dock without covering browse actions', async () => {
    const { player } = await start(); focus('hero-play');
    player.error = {code:'PLAYBACK_RESOLVE_FAILED',message:'无法恢复上次播放',errorId:1,retryable:true};
    await flushPromises();
    expect(wrapper!.find('.command-alert').exists()).toBe(false);
    expect(wrapper!.get('[data-focus-id=mini-open]').text()).toContain('查看问题');
    expect(active()).toBe('hero-play');
    player.error=null; player.commandFailure='操作失败'; await flushPromises();
    expect(wrapper!.find('.command-alert').exists()).toBe(false);
    expect(wrapper!.get('[data-focus-id=mini-open]').text()).toContain('操作未完成');
  });
  it('still exposes a failed first playback attempt when no dock exists', async () => {
    const { player } = await start('/home', false);
    player.commandFailure='首次播放失败，请重试'; await flushPromises();
    expect(wrapper!.get('.command-alert').text()).toContain('首次播放失败');
  });
  it('stops at both navigation edges without wrapping or activating a tab', async () => {
    const { router } = await start();
    focus('nav-home'); await key('ArrowLeft'); expect(active()).toBe('nav-home');
    focus('account'); await key('ArrowRight'); expect(active()).toBe('account');
    expect(router.currentRoute.value.name).toBe('home');
  });
  it('removes FM from the top navigation without gaps in remote order', async () => {
    await start();
    expect(wrapper!.find('[data-focus-id=nav-fm]').exists()).toBe(false);
    focus('nav-search'); await key('ArrowRight'); expect(active()).toBe('nav-ranking');
    await key('ArrowRight'); expect(active()).toBe('nav-more');
    await key('ArrowLeft'); expect(active()).toBe('nav-ranking');
  });
  it('Down enters the selected page even when another tab has focus', async () => {
    const { router } = await start(); focus('nav-home');
    await key('ArrowRight'); expect(active()).toBe('nav-explore');
    await key('ArrowDown'); expect(router.currentRoute.value.name).toBe('home'); expect(active()).toBe('hero-play');
  });
  it('does not let a pending confirmation steal later directional focus', async () => {
    const { router } = await start(); focus('nav-search');
    for (const name of ['Enter', 'ArrowLeft', 'ArrowRight', 'ArrowLeft']) {
      document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key:name, bubbles:true,cancelable:true }));
    }
    await flushPromises();
    expect(router.currentRoute.value.name).toBe('search'); expect(active()).toBe('nav-library');
    await key('Enter'); expect(router.currentRoute.value.name).toBe('library'); expect(active()).toBe('library-login');
  });
  it('keeps page focus scoped during repeated Library Search round trips', async () => {
    const { router } = await start('/library');
    for (let round = 0; round < 8; round++) {
      focus('nav-search'); await key('Enter'); expect(router.currentRoute.value.name).toBe('search');
      focus('search-hot-1'); await key('GoBack');
      focus('nav-library'); await key('Enter'); expect(router.currentRoute.value.name).toBe('library');
      expect(active()).toBe('library-login');
    }
  });
  it('uses Left/Right for header selection, Down for content, and Back for navigation before exit', async () => {
    const { router } = await start(); focus('nav-home');
    await key('ArrowRight'); expect(active()).toBe('nav-explore'); expect(router.currentRoute.value.name).toBe('home');
    await key('ArrowLeft'); await key('ArrowDown'); expect(active()).toBe('hero-play');
    await key('GoBack'); expect(active()).toBe('nav-home'); expect(wrapper!.find('[role=dialog]').exists()).toBe(false);
    await key('ArrowDown'); expect(active()).toBe('hero-play');
    await key('GoBack'); await key('GoBack'); expect(active()).toBe('exit-cancel');
  });
  it('Menu reaches lyrics from content, repeat does not bounce, Up and Back restore the source', async () => {
    await start('/search'); focus('search-hot-1');
    await key('ContextMenu'); expect(active()).toBe('mini-open');
    await key('ContextMenu', true); expect(active()).toBe('mini-open');
    await key('ArrowUp'); expect(active()).toBe('search-hot-1');
    await key('ContextMenu'); await key('GoBack'); expect(active()).toBe('search-hot-1');
  });
  it('opens the player without restarting playback and returns to its content origin', async () => {
    const { router } = await start('/search'); focus('search-hot-1');
    const surface = wrapper!.get<HTMLElement>('.content-surface').element; surface.scrollTop = 260;
    await key('ContextMenu'); await key('Enter'); expect(router.currentRoute.value.name).toBe('now-playing');
    expect(request.mock.calls.some(([method]) => String(method).startsWith('player.'))).toBe(false);
    await key('GoBack'); expect(router.currentRoute.value.name).toBe('search');
    expect(active()).toBe('search-hot-1'); expect(surface.scrollTop).toBe(260);
  });
  it('keeps the five-key-only route through the header playing entry', async () => {
    const { router } = await start(); focus('nav-more'); await key('ArrowRight');
    expect(active()).toBe('nav-playing'); await key('Enter'); expect(router.currentRoute.value.name).toBe('now-playing');
    expect(request.mock.calls.some(([method]) => String(method).startsWith('player.'))).toBe(false);
  });
  it('content bottom enters the dock even when the page defines a self-link', async () => {
    await start('/search');
    const last = wrapper!.get<HTMLElement>('[data-focus-id=search-hot-1]').element;
    last.getBoundingClientRect = () => new DOMRect(200, 900, 200, 70);
    focus('search-hot-1'); await key('ArrowDown'); expect(active()).toBe('mini-open');
    await key('ArrowUp'); expect(active()).toBe('search-hot-1');
  });
  it('never steals Menu from an IME or modal dialog', async () => {
    await start('/search'); focus('search-input'); await key('Enter');
    expect(wrapper!.get<HTMLInputElement>('input').element.readOnly).toBe(false);
    await key('ContextMenu'); expect(active()).toBe('search-input');
    await key('GoBack'); expect(active()).toBe('search-submit');
    focus('account'); await key('Enter');
    const before = active(); await key('ContextMenu'); expect(active()).toBe(before); expect(active()).not.toBe('mini-open');
  });
  it('no-track Menu is harmless and removing playback while focused restores browsing', async () => {
    const { player } = await start('/search', false); focus('search-hot-0');
    await key('ContextMenu'); expect(active()).toBe('search-hot-0');
    player.applySnapshot(playing); await flushPromises(); await key('ContextMenu'); expect(active()).toBe('mini-open');
    player.applySnapshot({ ...playing, currentTrack: null, queue: [], queueIndex: -1 }); await flushPromises();
    expect(active()).toBe('search-hot-0'); expect(wrapper!.find('[data-mini-player]').exists()).toBe(false);
  });
});
