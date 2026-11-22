import { createPinia } from 'pinia';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installNativeClientForTest } from '../native/client';
import { createTvRouter, TV_NAV_ITEMS } from '../router';
import { useTvAppStore } from '../stores/app';
import { useTvContentStore } from '../stores/content';
import { useTvPlayerStore } from '../stores/player';
import { useTvDetailStore } from '../stores/detail';
import TvShell from './TvShell.vue';
import { vTvFocus } from '../focus/directive';

enableAutoUnmount(afterEach);
afterEach(() => delete window.EchoTvNative);

const nativeRequest = vi.fn().mockResolvedValue({ accepted: true });
const playingSnapshot = {
  currentTrack: {
    id: 'tv_track',
    title: '蓝色时刻',
    artists: ['Echo Sessions'],
    album: 'Blue Hour',
    durationMs: 221_000,
    qualities: ['high'],
  },
  isPlaying: true,
  positionMs: 10_000,
  durationMs: 221_000,
  queue: [],
  queueIndex: -1,
  repeatMode: 'off',
  quality: 'high',
  lyrics: [],
  error: null,
  errorRevision: 0,
} as const;

const homeFixture = {
  greeting: '晚上好',
  hero: {
    id: 'echo-blue-hour', title: '蓝色时刻', artist: 'Echo Sessions', album: 'Blue Hour',
    artwork: 'https://example.invalid/blue-hour.jpg', durationMs: 221_000,
  },
  secondary: [],
  shelves: [
    {
      id: 'recent', title: '最近常听', capability: 'play',
      tracks: [{
        id: 'echo-blue-hour', title: '蓝色时刻', artist: 'Echo Sessions', album: 'Blue Hour',
        artwork: 'https://example.invalid/blue-hour.jpg', durationMs: 221_000,
      }],
    },
    {
      id: 'for-you', title: '为你精选', capability: 'play',
      tracks: [{
        id: 'afterglow', title: '余晖', artist: 'Northbound', album: 'Afterglow',
        artwork: 'https://example.invalid/afterglow.jpg', durationMs: 215_000,
      }],
    },
  ],
};

function seedHome(pinia = createPinia()) {
  void useTvContentStore(pinia).loadHome(homeFixture);
  return pinia;
}

beforeEach(() => {
  nativeRequest.mockClear();
  nativeRequest.mockImplementation((method: string, payload: { errorId?: number }) => Promise.resolve(
    method === 'player.clearError'
      ? { accepted: true, errorRevision: (payload.errorId ?? 0) + 1 }
      : { accepted: true },
  ));
  installNativeClientForTest({ request: nativeRequest, on: () => () => undefined });
});

describe('TvShell', () => {
  it('opens listening after a successful play intent and restores the originating card and scroll on Back', async () => {
    const router = createTvRouter();
    const pinia = seedHome();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    await flushPromises();
    const origin = wrapper.get('[data-focus-id="hero-play"]').element as HTMLElement;
    origin.focus();
    const scroll = wrapper.get('.content-surface').element as HTMLElement;
    scroll.scrollTop = 320;
    await useTvPlayerStore(pinia).playQueue(['echo-blue-hour'], 0);
    await flushPromises();
    expect(router.currentRoute.value.name).toBe('now-playing');
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));
    await flushPromises();
    expect(router.currentRoute.value.name).toBe('home');
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('hero-play');
    expect(scroll.scrollTop).toBe(320);
  });

  it.each([false, true])('returns from MV to its artist card with evicted cache=%s without a nav fallback', async (evicted) => {
    const empty = { items: [], nextCursor: null, total: 0 };
    const videos = { items: [{ id: 'mv:48509', title: '返回现场', subtitle: '测试歌手', kind: 'mv', artwork: '' }], nextCursor: null, total: 1 };
    let returning = false;
    let resolveVideos!: (value: unknown) => void;
    nativeRequest.mockImplementation((method: string, payload: { kind?: string; tab?: string }) => {
      if (method === 'content.detail' && payload.kind === 'artist') return Promise.resolve({
        id: 'artist:shell-return', title: '歌手', kind: 'artist', artwork: '', summary: '', tabs: ['songs', 'albums', 'mv'],
        hotTracks: [], children: { ...empty, total: null },
      });
      if (method === 'content.detailChildren') return payload.tab === 'mv'
        ? returning ? new Promise(done => { resolveVideos = done; }) : Promise.resolve(videos)
        : Promise.resolve({ ...empty, tracks: [] });
      if (method === 'video.getSnapshot') return Promise.resolve({ durationMs: 0, positionMs: 0, playing: false, quality: 'hd', errorCode: null, open: true, operation: null });
      if (method === 'video.open') return Promise.resolve({ accepted: true, quality: 'hd' });
      return Promise.resolve({ accepted: true });
    });
    const router = createTvRouter();
    await router.push('/artist/artist:shell-return'); await router.isReady();
    const pinia = createPinia();
    const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    await flushPromises();
    await wrapper.get('[data-focus-id=artist-tab-mv]').trigger('click'); await flushPromises();
    const video = wrapper.get('[data-focus-id=artist-mv-0]');
    (video.element as HTMLElement).focus(); await video.trigger('click'); await flushPromises();
    expect(router.currentRoute.value.name).toBe('mv-detail');
    returning = true;
    if (evicted) {
      const detail = useTvDetailStore(pinia);
      delete detail.children['artist:shell-return:mv'];
      delete detail.childrenStatus['artist:shell-return:mv'];
    }
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' })); await flushPromises();
    expect(router.currentRoute.value.name).toBe('artist-detail');
    if (evicted) {
      expect((document.activeElement as HTMLElement).dataset.focusId).toBe('artist-tab-mv');
      resolveVideos(videos); await flushPromises();
    } else expect(resolveVideos).toBeUndefined();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('artist-mv-0');
  });

  it('does not leave browsing on failed playback or jump back after the user changes pages', async () => {
    const router = createTvRouter();
    const pinia = seedHome();
    await router.push('/home');
    await router.isReady();
    mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    await flushPromises();
    nativeRequest.mockResolvedValue({ accepted: false });
    await expect(useTvPlayerStore(pinia).playQueue(['echo-blue-hour'], 0)).rejects.toThrow();
    expect(router.currentRoute.value.name).toBe('home');
    let accept!: (value: unknown) => void;
    nativeRequest.mockImplementation(method => method === 'player.playQueue'
      ? new Promise(done => { accept = done; }) : Promise.resolve({ accepted: true }));
    const pending = useTvPlayerStore(pinia).playQueue(['echo-blue-hour'], 0);
    await router.push('/search');
    accept({ accepted: true });
    await pending;
    await flushPromises();
    expect(router.currentRoute.value.name).toBe('search');
  });

  it('prioritizes listening, discovery, library and search while retaining secondary destinations', async () => {
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();

    const wrapper = mount(TvShell, {
      global: { plugins: [createPinia(), router] },
    });

    expect(wrapper.findAll('[data-nav-id]').map((item) => item.attributes('data-nav-id'))).toEqual([
      'home',
      'explore',
      'library',
      'search',
      'ranking',
      'more',
    ]);
  });

  it('keeps every navigation destination reachable', async () => {
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      global: { plugins: [createPinia(), router] },
    });

    for (const destination of TV_NAV_ITEMS) {
      await router.push({ name: destination.id });
      await flushPromises();

      expect(router.currentRoute.value.name).toBe(destination.id);
      expect(wrapper.find(`[data-page="${destination.id}"]`).exists()).toBe(true);
    }
  });

  it('resets the shared content scroll position when a new route opens', async () => {
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [createPinia(), router] },
    });
    const surface = wrapper.get('.content-surface').element as HTMLElement;
    surface.scrollTop = 840;

    await router.push('/search');
    await nextTick();

    expect(surface.scrollTop).toBe(0);
    wrapper.unmount();
  });

  it('shows the current track in the mini player only after playback starts', async () => {
    const router = createTvRouter();
    const pinia = createPinia();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } },
    });

    expect(wrapper.find('[data-mini-player]').exists()).toBe(false);

    useTvPlayerStore(pinia).applySnapshot(playingSnapshot);
    await nextTick();

    expect(wrapper.get('[data-mini-player]').text()).toContain('蓝色时刻');
    expect(wrapper.get('[data-mini-player]').text()).toContain('Echo Sessions');
    await wrapper.get('[data-focus-id="mini-toggle"]').trigger('click');
    expect(nativeRequest).toHaveBeenCalledWith('player.toggle', {});
    expect(useTvPlayerStore(pinia).isPlaying).toBe(true);
  });

  it('does not stack the MiniPlayer over the immersive Now Playing route', async () => {
    const router = createTvRouter();
    const pinia = createPinia();
    await router.push('/now-playing');
    await router.isReady();
    useTvPlayerStore(pinia).applySnapshot(playingSnapshot);
    const wrapper = mount(TvShell, { global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    await nextTick();

    expect(wrapper.find('[data-page="now-playing"]').exists()).toBe(true);
    expect(wrapper.find('[data-mini-player]').exists()).toBe(false);
    expect(wrapper.findAll('[data-nav-id]')).toHaveLength(0);
    expect(wrapper.get('.shell-background').classes()).toContain('player-active');
  });

  it('preserves the idle page focus when ongoing lyrics replace DOM children', async () => {
    vi.useFakeTimers();
    const router = createTvRouter();
    const pinia = createPinia();
    await router.push('/now-playing');
    await router.isReady();
    const player = useTvPlayerStore(pinia);
    player.applySnapshot(playingSnapshot);
    const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    try {
      await flushPromises();
      const queue = wrapper.get('[data-focus-id="player-queue"]').element as HTMLElement;
      queue.focus();
      await vi.advanceTimersByTimeAsync(10_000);
      const page = wrapper.get('[data-page="now-playing"]');
      expect(page.attributes('data-player-idle')).toBe('true');
      expect(document.activeElement).toBe(page.element);
      player.applySnapshot({ ...playingSnapshot, positionMs: 11_000, lyrics: [{ startMs: 0, endMs: 20_000, text: 'Updated lyrics' }] });
      await flushPromises();
      await nextTick();
      expect(document.activeElement).toBe(page.element);
      const wake = new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true });
      page.element.dispatchEvent(wake);
      await flushPromises();
      expect(wake.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(queue);
      expect(wrapper.find('[aria-label="播放队列"]').exists()).toBe(false);
    } finally {
      wrapper.unmount();
      vi.useRealTimers();
    }
  });

  it('hands focus from MiniPlayer to Now Playing, empty state, and back again', async () => {
    const router = createTvRouter();
    const pinia = createPinia();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    const player = useTvPlayerStore(pinia);
    player.applySnapshot(playingSnapshot);
    await nextTick();
    await nextTick();
    const miniOpen = wrapper.get('[data-focus-id="mini-open"]').element as HTMLElement;
    miniOpen.focus();

    await wrapper.get('[data-focus-id="mini-open"]').trigger('click');
    await flushPromises();
    await nextTick();
    expect(router.currentRoute.value.name).toBe('now-playing');
    expect(document.activeElement).toBe(wrapper.get('[data-focus-id="player-toggle"]').element);

    player.applySnapshot({ ...playingSnapshot, currentTrack: null, isPlaying: false, positionMs: 0, durationMs: 0, queue: [], queueIndex: -1 });
    await nextTick();
    await nextTick();
    expect(document.activeElement).toBe(wrapper.get('[data-focus-id="player-empty-home"]').element);

    player.applySnapshot(playingSnapshot);
    await nextTick();
    await nextTick();
    expect(document.activeElement).toBe(wrapper.get('[data-focus-id="player-toggle"]').element);
    await router.push('/home');
    await flushPromises();
    await nextTick();
    expect(document.activeElement).toBe(wrapper.get('[data-focus-id="mini-open"]').element);
    wrapper.unmount();
  });

  it('turns the account control into an explicit login request', async () => {
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [createPinia(), router] },
    });
    const requested = vi.fn();
    window.addEventListener('echo-tv-login-requested', requested);

    await wrapper.get('[data-focus-id="account"]').trigger('click');

    expect(requested).toHaveBeenCalledOnce();
    window.removeEventListener('echo-tv-login-requested', requested);
  });

  it('routes Back from a secondary destination to the previous page', async () => {
    const router = createTvRouter();
    await router.push('/home');
    await router.push('/search');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [createPinia(), router] },
    });

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));
    await flushPromises();

    expect(router.currentRoute.value.name).toBe('home');
    wrapper.unmount();
  });

  it('routes Back from a directly restored primary destination to Home without browser history', async () => {
    const router = createTvRouter();
    await router.replace('/search');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [createPinia(), router] },
    });

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'GoBack' }));
    await flushPromises();

    expect(router.currentRoute.value.name).toBe('home');
    wrapper.unmount();
  });

  it('asks for confirmation instead of leaving when Back is pressed on Home', async () => {
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [createPinia(), router] },
    });

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));
    await nextTick();

    expect(wrapper.get('[role="dialog"]').text()).toContain('退出 EchoMusic');
    wrapper.unmount();
  });

  it('moves real focus between nav destinations with the D-pad', async () => {
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [createPinia(), router] },
    });
    const home = wrapper.get('[data-focus-id="nav-home"]').element as HTMLElement;
    const explore = wrapper.get('[data-focus-id="nav-explore"]').element as HTMLElement;
    home.getBoundingClientRect = () => new DOMRect(0, 0, 72, 72);
    explore.getBoundingClientRect = () => new DOMRect(88, 0, 72, 72);

    home.focus();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));

    expect(document.activeElement).toBe(explore);
    wrapper.unmount();
  });

  it('hands focus from the top navigation into the first ready Explore card', async () => {
    window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
    nativeRequest.mockImplementation((method: string) => Promise.resolve(
      method === 'content.explore'
        ? {
            newSongs: [{ id: 'song:first', title: '首支新歌', kind: 'song', subtitle: '测试歌手', artwork: '' }],
            newAlbums: [],
            newPlaylists: [],
            artists: [],
            rankings: [],
          }
        : { accepted: true },
    ));
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [createPinia(), router] },
    });
    await flushPromises();
    const explore = wrapper.get('[data-focus-id="nav-explore"]').element as HTMLElement;
    explore.focus();

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await flushPromises();
    await nextTick();

    expect(router.currentRoute.value.name).toBe('explore');
    expect(document.activeElement).toBe(wrapper.get('[data-focus-id="explore-newSongs-0"]').element);
    expect(wrapper.findAll('[data-page="explore"] > .shelf')).toHaveLength(1);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'GoBack' }));
    expect(document.activeElement).toBe(wrapper.get('[data-focus-id="nav-explore"]').element);
    wrapper.unmount();
  });

  it('hands focus from the top navigation into the logged-out Library action', async () => {
    const router = createTvRouter();
    const pinia = createPinia();
    useTvAppStore(pinia).session = { status: 'loggedOut' };
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } },
    });
    await flushPromises();
    const library = wrapper.get('[data-focus-id="nav-library"]').element as HTMLElement;
    library.focus();

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await flushPromises();
    await nextTick();

    expect(router.currentRoute.value.name).toBe('library');
    const login = wrapper.get('[data-focus-id="library-login"]').element as HTMLElement;
    expect(document.activeElement).toBe(login);
    expect(getComputedStyle(login).fontSize).toBe('21px');
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'GoBack' }));
    expect(document.activeElement).toBe(wrapper.get('[data-focus-id="nav-library"]').element);
    wrapper.unmount();
  });

  it('activates the focused control when the remote sends Enter', async () => {
    const router = createTvRouter();
    const pinia = seedHome();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } },
    });
    const hero = wrapper.get('[data-focus-id="hero-play"]').element as HTMLElement;
    hero.focus();

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await nextTick();

    expect(nativeRequest).toHaveBeenCalledWith(
      'player.playQueue',
      expect.objectContaining({ trackIds: expect.arrayContaining(['echo-blue-hour']), startIndex: 0 }),
    );
    expect(wrapper.find('[data-mini-player]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('forwards hardware media keys without changing projected state', async () => {
    const router = createTvRouter();
    const pinia = createPinia();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    useTvPlayerStore(pinia).applySnapshot(playingSnapshot);
    await nextTick();
    await flushPromises();
    nativeRequest.mockClear();

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'MediaPlayPause' }));
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'MediaTrackPrevious' }));
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'MediaTrackNext' }));

    expect(nativeRequest.mock.calls).toEqual([
      ['player.toggle', {}],
      ['player.previous', {}],
      ['player.next', {}],
    ]);
    expect(useTvPlayerStore(pinia).isPlaying).toBe(true);
    wrapper.unmount();
  });

  it('routes hardware media keys to video while the MV route owns playback', async () => {
    const router = createTvRouter();
    await router.push({ name: 'mv-detail', params: { id: 'mv:42' }, query: { quality: 'hd' } });
    await router.isReady();
    const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [createPinia(), router] } });
    await nextTick();
    nativeRequest.mockClear();

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'MediaPlayPause' }));
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'MediaTrackPrevious' }));
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'MediaTrackNext' }));
    await flushPromises();

    expect(nativeRequest.mock.calls).toEqual([
      ['video.toggle', {}],
      ['video.seekBy', { deltaMs: -10_000 }],
      ['video.seekBy', { deltaMs: 10_000 }],
    ]);
    expect(nativeRequest.mock.calls.some(([method]) => String(method).startsWith('player.'))).toBe(false);
    wrapper.unmount();
  });

  it('restores D-pad focus when the native snapshot removes the focused mini-player', async () => {
    const router = createTvRouter();
    const pinia = createPinia();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    const player = useTvPlayerStore(pinia);
    player.applySnapshot(playingSnapshot);
    await nextTick();
    const nav = wrapper.get('[data-focus-id="nav-home"]').element as HTMLElement;
    const mini = wrapper.get('[data-focus-id="mini-toggle"]').element as HTMLElement;
    nav.focus();
    mini.focus();

    player.applySnapshot({
      ...playingSnapshot,
      currentTrack: null,
      isPlaying: false,
      positionMs: 0,
      durationMs: 0,
      queue: [],
      queueIndex: -1,
    });
    await nextTick();
    await nextTick();

    expect(wrapper.find('[data-mini-player]').exists()).toBe(false);
    expect(document.activeElement).toBe(nav);
    wrapper.unmount();
  });

  it.each([false, true])('uses the page fallback after detached focus without reclaiming deliberate navigation: %s', async (moveToNav) => {
    const router = createTvRouter();
    const pinia = seedHome();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    await flushPromises();
    const page = wrapper.get('[data-page=home]').element as HTMLElement;
    const ready = document.createElement('button');
    ready.dataset.focusId = 'page-ready-primary';
    page.dataset.defaultFocusId = 'page-ready-primary';
    page.append(ready);
    const hero = wrapper.get('[data-focus-id=hero-play]').element as HTMLElement;
    const nav = wrapper.get('[data-focus-id=nav-home]').element as HTMLElement;
    hero.focus();
    if (moveToNav) nav.focus();
    hero.remove();
    await nextTick();
    await flushPromises();
    expect(document.activeElement).toBe(moveToNav ? nav : ready);
    wrapper.unmount();
  });

  it('restores a safe focus target when async content removes the focused card', async () => {
    const router = createTvRouter();
    const pinia = seedHome();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } },
    });
    const nav = wrapper.get('[data-focus-id="nav-home"]').element as HTMLElement;
    const hero = wrapper.get('[data-focus-id="hero-play"]').element as HTMLElement;
    hero.focus();

    hero.remove();
    await nextTick();
    await Promise.resolve();
    await nextTick();

    expect(document.activeElement).toBe(nav);
    wrapper.unmount();
  });

  it('establishes a deterministic initial focus on cold start', async () => {
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [createPinia(), router] },
    });

    expect(document.activeElement).toBe(wrapper.get('[data-focus-id="nav-home"]').element);
    wrapper.unmount();
  });

  it('traps D-pad focus in the exit dialog and invokes the native lifecycle command', async () => {
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [createPinia(), router] },
    });
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));
    await nextTick();
    const background = wrapper.get('.shell-background');
    const cancel = wrapper.get('[data-focus-id="exit-cancel"]').element as HTMLElement;
    const confirm = wrapper.get('[data-focus-id="exit-confirm"]').element as HTMLElement;
    cancel.getBoundingClientRect = () => new DOMRect(0, 0, 150, 52);
    confirm.getBoundingClientRect = () => new DOMRect(170, 0, 150, 52);

    expect(background.attributes()).toHaveProperty('inert');
    expect(document.activeElement).toBe(cancel);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    expect(document.activeElement).toBe(confirm);
    await wrapper.get('[data-focus-id="exit-confirm"]').trigger('click');

    expect(nativeRequest).toHaveBeenCalledWith('app.exit', {});
    wrapper.unmount();
  });

  it('keeps spatial D-pad navigation inside an active TV text editor', async () => {
    const router = createTvRouter();
    await router.push('/search');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [createPinia(), router] },
    });
    await flushPromises();
    const input = wrapper.get('[data-focus-id="search-input"]').element as HTMLInputElement;
    const submit = wrapper.get('[data-focus-id="search-submit"]').element as HTMLElement;
    input.getBoundingClientRect = () => new DOMRect(100, 100, 500, 60);
    submit.getBoundingClientRect = () => new DOMRect(620, 100, 130, 60);
    input.focus();
    expect(input.readOnly).toBe(true);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
    expect(document.activeElement).toBe(submit);

    input.focus();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await flushPromises();
    expect(input.readOnly).toBe(false);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
    expect(document.activeElement).toBe(input);
    wrapper.unmount();
  });

  it('closes the TV IME layer before route navigation and leaves a visible focus target', async () => {
    const router = createTvRouter();
    await router.push('/home');
    await router.push('/search');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [createPinia(), router] },
    });
    await flushPromises();
    const input = wrapper.get('[data-focus-id="search-input"]').element as HTMLInputElement;
    const submit = wrapper.get('[data-focus-id="search-submit"]').element as HTMLElement;
    input.focus();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await nextTick();

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await nextTick();

    expect(router.currentRoute.value.name).toBe('search');
    expect(document.activeElement).toBe(submit);
    // Android WebView may briefly bounce focus back to the editor while the
    // IME hide animation is settling. The native completion event must win.
    input.focus();
    window.dispatchEvent(new CustomEvent('echo-tv-ime-hidden'));
    await nextTick();
    expect(document.activeElement).toBe(submit);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await flushPromises();
    expect(router.currentRoute.value.name).toBe('search');
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('nav-search');
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await flushPromises();
    expect(router.currentRoute.value.name).toBe('home');
    wrapper.unmount();
  });

  it('traps D-pad and Back in the queue drawer before returning focus to its trigger', async () => {
    const router = createTvRouter();
    const pinia = createPinia();
    await router.push('/now-playing');
    await router.isReady();
    useTvPlayerStore(pinia).applySnapshot({
      ...playingSnapshot,
      queue: [playingSnapshot.currentTrack!, { ...playingSnapshot.currentTrack!, id: 'second-track', title: '下一首' }],
      queueIndex: 0,
    });
    const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    await nextTick();
    const trigger = wrapper.get('[data-focus-id="player-queue"]').element as HTMLElement;
    const close = () => wrapper.get('[data-focus-id="queue-close"]').element as HTMLElement;
    trigger.focus();
    await wrapper.get('[data-focus-id="player-queue"]').trigger('click');
    await nextTick();
    await nextTick();

    expect(wrapper.get('.now-playing-content').attributes()).toHaveProperty('inert');
    expect(document.activeElement).toBe(wrapper.get('[data-focus-id="queue-track-tv_track"]').element);
    close().getBoundingClientRect = () => new DOMRect(0, 0, 80, 40);
    wrapper.get('[data-focus-id="queue-track-second-track"]').element.getBoundingClientRect = () => new DOMRect(100, 0, 160, 40);
    trigger.getBoundingClientRect = () => new DOMRect(50, 0, 100, 40);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    expect(document.activeElement).toBe(wrapper.get('[data-focus-id="queue-track-second-track"]').element);

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));
    await nextTick();
    expect(wrapper.find('[role="dialog"][aria-label="播放队列"]').exists()).toBe(false);
    expect(document.activeElement).toBe(trigger);
    expect(router.currentRoute.value.name).toBe('now-playing');
    wrapper.unmount();
  });

  it.each([
    ['player-quality', 'quality-high'],
    ['player-sound-effect', 'sound-effect-off'],
  ])('treats %s as a Back-closing drawer and restores the trigger', async (triggerId, initialFocusId) => {
    const router = createTvRouter();
    const pinia = createPinia();
    await router.push('/now-playing');
    await router.isReady();
    useTvPlayerStore(pinia).applySnapshot(playingSnapshot);
    const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    await nextTick();
    const trigger = wrapper.get(`[data-focus-id="${triggerId}"]`).element as HTMLElement;
    trigger.focus();
    await wrapper.get(`[data-focus-id="${triggerId}"]`).trigger('click');
    await nextTick();
    await nextTick();
    expect(document.activeElement).toBe(wrapper.get(`[data-focus-id="${initialFocusId}"]`).element);

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));
    await nextTick();
    expect(document.activeElement).toBe(trigger);
    expect(router.currentRoute.value.name).toBe('now-playing');
    wrapper.unmount();
  });

  it('treats a resolution error as a dialog Back layer and restores the interrupted player focus', async () => {
    const router = createTvRouter();
    const pinia = createPinia();
    await router.push('/now-playing');
    await router.isReady();
    const player = useTvPlayerStore(pinia);
    player.applySnapshot(playingSnapshot);
    const wrapper = mount(TvShell, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    await nextTick();
    const trigger = wrapper.get('[data-focus-id="player-toggle"]').element as HTMLElement;
    trigger.focus();
    player.applySnapshot({
      ...playingSnapshot,
      error: { errorId: 1, code: 'PLAYBACK_RESOLVE_FAILED', message: '当前歌曲暂时无法播放', retryable: true },
      errorRevision: 1,
    });
    await nextTick();
    await nextTick();
    await nextTick();
    expect(wrapper.get('[data-focus-id="playback-error-retry"]').element.closest('[inert]')).toBeNull();
    expect(document.activeElement).toBe(wrapper.get('[data-focus-id="playback-error-retry"]').element);

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));
    await flushPromises();
    await nextTick();
    expect(wrapper.find('[data-focus-id="playback-error-retry"]').exists()).toBe(false);
    expect(nativeRequest).toHaveBeenLastCalledWith('player.clearError', { errorId: 1 });
    expect(document.activeElement).toBe(trigger);
    wrapper.unmount();
  });

  it('keeps focused content visible through nav, hero, listening shortcuts, shelves and mini-player', async () => {
    const router = createTvRouter();
    const pinia = createPinia();
    seedHome(pinia);
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } },
    });
    const nav = wrapper.get('[data-focus-id="nav-home"]').element as HTMLElement;
    const hero = wrapper.get('[data-focus-id="hero-play"]').element as HTMLElement;
    const shelfOne = wrapper.get('[data-focus-id="recent-echo-blue-hour"]').element as HTMLElement;
    const shelfTwo = wrapper.get('[data-focus-id="for-you-afterglow"]').element as HTMLElement;
    const scrollIntoView = vi.fn();
    hero.scrollIntoView = scrollIntoView;
    nav.getBoundingClientRect = () => new DOMRect(0, 0, 72, 62);
    hero.getBoundingClientRect = () => new DOMRect(200, 0, 166, 52);
    shelfOne.getBoundingClientRect = () => new DOMRect(200, 180, 240, 180);
    shelfTwo.getBoundingClientRect = () => new DOMRect(200, 400, 240, 180);

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    await flushPromises();
    expect(document.activeElement).toBe(hero);
    expect(scrollIntoView).not.toHaveBeenCalled();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('home-quick-daily-recommendations');
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    expect(document.activeElement).toBe(shelfOne);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    expect(document.activeElement).toBe(shelfTwo);

    useTvPlayerStore(pinia).applySnapshot({
      ...playingSnapshot,
      currentTrack: { ...playingSnapshot.currentTrack, id: 'track', title: '曲目', artists: ['歌手'], album: '专辑', durationMs: 180_000 },
      durationMs: 180_000,
    });
    await nextTick();
    await Promise.resolve();
    const mini = wrapper.get('[data-focus-id="mini-open"]').element as HTMLElement;
    mini.getBoundingClientRect = () => new DOMRect(900, 650, 60, 48);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    expect(document.activeElement).toBe(mini);
    wrapper.unmount();
  });

  it('scrolls a focused content card above the floating MiniPlayer when their rectangles overlap', async () => {
    const router = createTvRouter();
    const pinia = createPinia();
    seedHome(pinia);
    await router.push('/home');
    await router.isReady();
    const wrapper = mount(TvShell, {
      attachTo: document.body,
      global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } },
    });
    useTvPlayerStore(pinia).applySnapshot(playingSnapshot);
    await nextTick();

    const target = wrapper.get('[data-focus-id="for-you-afterglow"]').element as HTMLElement;
    const mini = wrapper.get('[data-mini-player]').element as HTMLElement;
    const surface = wrapper.get('.content-surface').element as HTMLElement;
    const scrollBy = vi.fn();
    surface.scrollBy = scrollBy;
      Object.defineProperty(surface, 'offsetHeight', { configurable: true, value: 2160 });
      surface.getBoundingClientRect = () => new DOMRect(0, 27, 1920, 1080);
    target.getBoundingClientRect = () => new DOMRect(900, 640, 240, 180);
    mini.getBoundingClientRect = () => new DOMRect(880, 700, 900, 116);

    target.focus();
    await nextTick();

    expect(scrollBy).toHaveBeenCalledWith(expect.objectContaining({ top: 304 }));
    wrapper.unmount();
  });
});
