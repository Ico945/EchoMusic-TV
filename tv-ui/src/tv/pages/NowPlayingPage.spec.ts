import { createPinia, setActivePinia } from 'pinia';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installNativeClientForTest } from '../native/client';
import { createTvRouter } from '../router';
import { useTvPlayerStore } from '../stores/player';
import NowPlayingPage from './NowPlayingPage.vue';
import { vTvFocus } from '../focus/directive';

enableAutoUnmount(afterEach);

const snapshot = {
  currentTrack: {
    id: 'tv_track_1',
    title: '飞行模式',
    artists: ['华晨宇'],
    album: '新世界 NEW WORLD',
    durationMs: 240_000,
    qualities: ['128', '320', 'flac'],
    artworkUrl: 'https://imge.kugou.com/stdmusic/400/cover.jpg',
  },
  isPlaying: true,
  positionMs: 1_500,
  durationMs: 240_000,
  queue: [],
  queueIndex: -1,
  repeatMode: 'off',
  quality: 'flac',
  lyrics: [
    { startMs: 0, endMs: 999, text: '当天空突然安静' },
    { startMs: 1_000, endMs: 2_499, text: '云朵停在原地' },
    { startMs: 2_500, endMs: 4_000, text: '我开始练习飞行' },
  ],
  error: null,
  errorRevision: 0,
} as const;

describe('NowPlayingPage', () => {
  const request = vi.fn().mockResolvedValue({ accepted: true });

  beforeEach(() => {
    request.mockClear();
    installNativeClientForTest({ request, on: () => () => undefined });
  });

  afterEach(() => vi.useRealTimers());

  async function mountPage() {
    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createTvRouter();
    await router.push('/now-playing');
    await router.isReady();
    useTvPlayerStore(pinia).applySnapshot(snapshot);
    return { wrapper: mount(NowPlayingPage, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } }), pinia, router };
  }

  it('renders the approved lyrics-first player from native state', async () => {
    const { wrapper } = await mountPage();

    expect(wrapper.get('[data-page="now-playing"]').attributes('data-mode')).toBe('lyrics');
    expect(wrapper.text()).toContain('飞行模式');
    expect(wrapper.text()).toContain('华晨宇');
    expect(wrapper.get('[data-lyric-active="true"]').text()).toBe('云朵停在原地');
    expect(wrapper.get('[data-progress-fill]').attributes('style')).toContain('0.625%');
  });

  it('previews ten-second steps and commits a single track-bound native seek', async () => {
    const { wrapper, pinia } = await mountPage();
    const progress = wrapper.get('[data-focus-id="player-progress"]');

    await progress.trigger('keydown', { key: 'ArrowRight' });
    await progress.trigger('keydown', { key: 'ArrowRight', repeat: true });
    expect(request).not.toHaveBeenCalled();
    expect(progress.text()).toContain('0:21');
    await progress.trigger('click');
    expect(request.mock.calls).toEqual([['player.seekTo', { trackId: 'tv_track_1', positionMs: 21_500 }]]);
    expect(useTvPlayerStore(pinia).positionMs).toBe(1_500);
  });

  it('switches lyrics and cover presentation without restarting playback', async () => {
    const { wrapper } = await mountPage();

    await wrapper.get('[data-focus-id="mode-cover"]').trigger('click');
    expect(wrapper.get('[data-page="now-playing"]').attributes('data-mode')).toBe('cover');
    expect(wrapper.find('[data-focus-id="mode-portrait"]').exists()).toBe(false);
    await wrapper.get('[data-focus-id="mode-lyrics"]').trigger('click');
    expect(wrapper.get('[data-page="now-playing"]').attributes('data-mode')).toBe('lyrics');
    expect(request).not.toHaveBeenCalled();
  });

  it('replaces Task 4 status labels with actionable queue quality and repeat controls', async () => {
    const { wrapper } = await mountPage();

    expect(wrapper.text()).toContain('顺序');
    expect(wrapper.text()).toContain('无损');
    expect(wrapper.text()).toContain('队列 0');
    expect(wrapper.get('[data-focus-id="player-repeat"]').attributes('type')).toBe('button');
    expect(wrapper.get('[data-focus-id="player-quality"]').attributes('type')).toBe('button');
    expect(wrapper.get('[data-focus-id="player-queue"]').attributes('type')).toBe('button');
  });

  it('uses an explicit closed focus chain for modes, progress, and playback controls', async () => {
    const { wrapper } = await mountPage();

    expect(wrapper.get('[data-focus-id="mode-lyrics"]').attributes('data-focus-right')).toBe('mode-cover');
    expect(wrapper.get('[data-focus-id="mode-cover"]').attributes('data-focus-right')).toBe('mode-lyrics');
    expect(wrapper.get('[data-focus-id="player-progress"]').attributes('data-focus-up')).toBe('player-lyrics-panel');
    expect(wrapper.get('[data-focus-id="mode-cover"]').attributes('data-focus-down')).toBe('player-lyrics-panel');
    expect(wrapper.get('[data-focus-id="player-progress"]').attributes('data-focus-down')).toBe('player-toggle');
    expect(wrapper.get('[data-focus-id="player-previous"]').attributes('data-focus-right')).toBe('player-toggle');
    expect(wrapper.get('[data-focus-id="player-toggle"]').attributes('data-focus-right')).toBe('player-next');
    expect(wrapper.get('[data-focus-id="player-next"]').attributes('data-focus-right')).toBe('player-repeat');
    expect(wrapper.get('[data-focus-id="player-sound-effect"]').attributes('data-focus-right')).toBe('player-more');
    expect(wrapper.get('[data-focus-id="player-more"]').attributes('data-focus-right')).toBe('player-previous');
  });

  it('renders a recoverable empty state when opened before native playback exists', async () => {
    const pinia = createPinia();
    const router = createTvRouter();
    await router.push('/now-playing');
    await router.isReady();
    const wrapper = mount(NowPlayingPage, { global: { plugins: [pinia, router] } });

    expect(wrapper.get('[data-player-empty]').text()).toContain('还没有正在播放的歌曲');
    await wrapper.get('[data-focus-id="player-empty-home"]').trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.name).toBe('home');
  });

  it.each(['ArrowUp', 'ArrowLeft', 'ArrowRight'])('wakes with %s without changing tracks and restores the last focus', async (key) => {
    vi.useFakeTimers();
    const { wrapper } = await mountPage();
    const page = wrapper.get('[data-page="now-playing"]');
    const queue = wrapper.get('[data-focus-id="player-queue"]');
    (queue.element as HTMLElement).focus();

    expect(page.attributes('data-player-idle')).toBe('false');
    await vi.advanceTimersByTimeAsync(9_999);
    expect(page.attributes('data-player-idle')).toBe('false');

    await vi.advanceTimersByTimeAsync(1);
    await flushPromises();
    expect(page.attributes('data-player-idle')).toBe('true');
    expect(wrapper.get('.player-header').attributes()).toHaveProperty('inert');
    expect(wrapper.get('.bottom-controls').attributes()).toHaveProperty('inert');
    expect(document.activeElement).toBe(page.element);

    const wake = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
    window.dispatchEvent(wake);
    await flushPromises();

    expect(wake.defaultPrevented).toBe(true);
    expect(page.attributes('data-player-idle')).toBe('false');
    expect(document.activeElement).toBe(queue.element);
    expect(request).not.toHaveBeenCalled();
  });

  it('pauses with one confirmation press while immersive, without double activation', async () => {
    vi.useFakeTimers();
    const { wrapper } = await mountPage();
    await vi.advanceTimersByTimeAsync(10_000);
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await flushPromises();
    expect(wrapper.attributes('data-player-idle')).toBe('false');
    expect(request.mock.calls).toEqual([['player.toggle', {}]]);
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('player-toggle');
  });

  it('falls back to a cover stage when a track has no lyrics', async () => {
    const { wrapper, pinia } = await mountPage();
    useTvPlayerStore(pinia).applySnapshot({ ...snapshot, lyrics: [] });
    await flushPromises();
    expect(wrapper.attributes('data-mode')).toBe('cover');
    expect(wrapper.find('[data-focus-id="player-lyrics-panel"]').exists()).toBe(false);
    expect(wrapper.get('[data-focus-id="mode-lyrics"]').attributes()).toHaveProperty('disabled');
    expect(wrapper.get('[data-focus-id="player-progress"]').attributes('data-focus-up')).toBe('mode-cover');
  });

  it('keeps player chrome visible while a playback drawer is open', async () => {
    vi.useFakeTimers();
    const { wrapper } = await mountPage();

    await wrapper.get('[data-focus-id="player-queue"]').trigger('click');
    await flushPromises();
    await vi.advanceTimersByTimeAsync(10_000);

    expect(wrapper.get('[data-page="now-playing"]').attributes('data-player-idle')).toBe('false');
    expect(wrapper.find('[role="dialog"][aria-label="播放队列"]').exists()).toBe(true);
  });

  it('continuous native snapshots do not reset the ten-second idle deadline', async () => {
    vi.useFakeTimers();
    const { wrapper, pinia } = await mountPage();
    const player = useTvPlayerStore(pinia);
    for (let tick = 1; tick <= 20; tick += 1) {
      player.applySnapshot({ ...snapshot, positionMs: tick * 500 });
      await vi.advanceTimersByTimeAsync(500);
    }
    expect(wrapper.attributes('data-player-idle')).toBe('true');
    player.applySnapshot({ ...snapshot, positionMs: 11_000 });
    await flushPromises();
    expect(wrapper.attributes('data-player-idle')).toBe('true');
    expect(wrapper.get('[data-progress-fill]').attributes('style')).toContain('4.583');
  });

  it('same-track metadata refresh does not count as remote activity', async () => {
    vi.useFakeTimers();
    const { wrapper, pinia } = await mountPage();
    await vi.advanceTimersByTimeAsync(9_000);
    useTvPlayerStore(pinia).applySnapshot({ ...snapshot, currentTrack: { ...snapshot.currentTrack, title: 'Updated title' } });
    await vi.advanceTimersByTimeAsync(1_000);
    expect(wrapper.attributes('data-player-idle')).toBe('true');
    expect(wrapper.text()).toContain('Updated title');
  });

  it('paused playback keeps controls visible without an idle timer', async () => {
    vi.useFakeTimers();
    const { wrapper, pinia } = await mountPage();
    useTvPlayerStore(pinia).applySnapshot({ ...snapshot, isPlaying: false });
    await vi.advanceTimersByTimeAsync(30_000);
    expect(wrapper.attributes('data-player-idle')).toBe('false');
  });

  it('pausing an idle player restores focus and does not hide again', async () => {
    vi.useFakeTimers();
    const { wrapper, pinia } = await mountPage();
    const toggle = wrapper.get('[data-focus-id="player-toggle"]');
    (toggle.element as HTMLElement).focus();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(wrapper.attributes('data-player-idle')).toBe('true');
    useTvPlayerStore(pinia).applySnapshot({ ...snapshot, isPlaying: false });
    await flushPromises();
    expect(document.activeElement).toBe(toggle.element);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(wrapper.attributes('data-player-idle')).toBe('false');
  });

  it('a genuine track change reveals the idle controls and restores usable focus', async () => {
    vi.useFakeTimers();
    const { wrapper, pinia } = await mountPage();
    await vi.advanceTimersByTimeAsync(10_000);
    useTvPlayerStore(pinia).applySnapshot({ ...snapshot, currentTrack: { ...snapshot.currentTrack, id: 'next-track' } });
    await flushPromises();
    expect(wrapper.attributes('data-player-idle')).toBe('false');
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('player-toggle');
    await vi.advanceTimersByTimeAsync(10_000);
    expect(wrapper.attributes('data-player-idle')).toBe('true');
  });

  it('resuming playback starts a new full idle deadline', async () => {
    vi.useFakeTimers();
    const { wrapper, pinia } = await mountPage();
    const player = useTvPlayerStore(pinia);
    player.applySnapshot({ ...snapshot, isPlaying: false });
    await vi.advanceTimersByTimeAsync(5_000);
    player.applySnapshot(snapshot);
    await vi.advanceTimersByTimeAsync(9_999);
    expect(wrapper.attributes('data-player-idle')).toBe('false');
    await vi.advanceTimersByTimeAsync(1);
    expect(wrapper.attributes('data-player-idle')).toBe('true');
  });
});
