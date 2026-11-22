import { createPinia, setActivePinia } from 'pinia';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { createTvRouter } from '../router';
import { useTvPlayerStore } from '../stores/player';
import MiniPlayer from './MiniPlayer.vue';
import { vTvFocus } from '../focus/directive';

enableAutoUnmount(afterEach);

const snapshot = {
  currentTrack: {
    id: 'tv_track_1',
    title: '飞行模式',
    artists: ['华晨宇'],
    album: '新世界 NEW WORLD',
    durationMs: 240_000,
    qualities: ['high', 'lossless'],
    artworkUrl: 'https://imge.kugou.com/stdmusic/400/cover.jpg',
  },
  isPlaying: true,
  positionMs: 60_000,
  durationMs: 240_000,
  queue: [],
  queueIndex: -1,
  repeatMode: 'off',
  quality: 'flac',
  lyrics: [],
  error: null,
  errorRevision: 0,
} as const;

describe('MiniPlayer', () => {
  it('dispatches skip commands once while pending and explains queue boundaries', async () => {
    let resolve!: (value: unknown) => void;
    const request = vi.fn(() => new Promise(done => { resolve = done; }));
    installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });
    const pinia = createPinia(); setActivePinia(pinia);
    const router = createTvRouter(); await router.push('/home');
    const player = useTvPlayerStore(pinia);
    const queue = [snapshot.currentTrack, { ...snapshot.currentTrack, id: 'tv_track_2' }];
    player.applySnapshot({ ...snapshot, queue, queueIndex: 0 });
    const wrapper = mount(MiniPlayer, { global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
    await wrapper.get('[data-focus-id="mini-previous"]').trigger('click');
    expect(request).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('已是第一首');
    await wrapper.get('[data-focus-id="mini-next"]').trigger('click');
    await wrapper.get('[data-focus-id="mini-next"]').trigger('click');
    expect(request).toHaveBeenCalledExactlyOnceWith('player.next', {});
    resolve({ accepted: true }); await flushPromises();
    player.applySnapshot({ ...snapshot, currentTrack: queue[1], queue, queueIndex: 1 }); await flushPromises();
    await wrapper.get('[data-focus-id="mini-next"]').trigger('click');
    expect(request).toHaveBeenCalledTimes(1); expect(wrapper.text()).toContain('已是最后一首');
    await wrapper.get('[data-focus-id="mini-previous"]').trigger('click');
    expect(request).toHaveBeenLastCalledWith('player.previous', {});
    resolve({ accepted: true }); await flushPromises();
  });

  it('renders native metadata and controls, and opens Now Playing without restarting playback', async () => {
    const request = vi.fn().mockResolvedValue({ accepted: true });
    installNativeClientForTest({ request, on: () => () => undefined });
    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();
    const player = useTvPlayerStore(pinia);
    player.applySnapshot(snapshot);
    const wrapper = mount(MiniPlayer, { global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });

    expect(wrapper.text()).toContain('飞行模式');
    expect(wrapper.text()).toContain('华晨宇');
    expect(wrapper.text()).toContain('正在播放');
    expect(wrapper.findAll('[data-focus-id]')).toHaveLength(5);
    expect(wrapper.get('[data-progress-fill]').attributes('style')).toContain('25%');
    expect(wrapper.get('[data-focus-id="mini-open"]').attributes('data-focus-right')).toBe('mini-previous');
    expect(wrapper.get('[data-focus-id="mini-toggle"]').attributes('data-focus-right')).toBe('mini-next');
    expect(wrapper.get('[data-focus-id="mini-details"]').attributes('data-focus-left')).toBe('mini-next');

    await wrapper.get('[data-focus-id="mini-toggle"]').trigger('click');
    expect(request).toHaveBeenCalledWith('player.toggle', {});
    expect(player.isPlaying).toBe(true);

    await wrapper.get('[data-focus-id="mini-open"]').trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.name).toBe('now-playing');
    expect(request).toHaveBeenCalledTimes(1);

    await router.push('/home');
    await wrapper.get('[data-focus-id="mini-details"]').trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.query.panel).toBe('queue');
    expect(request).toHaveBeenCalledTimes(1);
  });
});
