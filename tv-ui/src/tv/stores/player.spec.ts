import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { useTvPlayerStore } from './player';

type Handler = (payload: unknown) => void;

function nativeHarness() {
  const handlers = new Map<string, Set<Handler>>();
  const request = vi.fn().mockResolvedValue({ accepted: true });
  const client: NativeClientLike = {
    request,
    on(event, handler) {
      const listeners = handlers.get(event) ?? new Set<Handler>();
      listeners.add(handler);
      handlers.set(event, listeners);
      return () => listeners.delete(handler);
    },
  };
  return {
    client,
    request,
    emit(event: string, payload: unknown) {
      handlers.get(event)?.forEach((handler) => handler(payload));
    },
    listenerCount(event: string) {
      return handlers.get(event)?.size ?? 0;
    },
  };
}

const track = {
  id: 'tv_track_1',
  title: '飞行模式',
  artists: ['华晨宇'],
  album: '新世界 NEW WORLD',
  durationMs: 240_000,
  qualities: ['high', 'lossless'],
  artworkUrl: 'https://imge.kugou.com/stdmusic/400/20200814/20200814161400993507.jpg',
};

const snapshot = {
  currentTrack: track,
  isPlaying: true,
  positionMs: 1_000,
  durationMs: 4_000,
  queue: [track],
  queueIndex: 0,
  repeatMode: 'all',
  quality: 'lossless',
  lyrics: [{ startMs: 0, endMs: 2_000, text: '让音乐开始' }],
  error: null,
  errorRevision: 0,
} as const;

describe('TV native player projection', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('projects the complete native snapshot without inventing queue state', () => {
    const store = useTvPlayerStore();

    expect(store.applySnapshot(snapshot)).toBe(true);

    expect(store.currentTrack?.id).toBe('tv_track_1');
    expect(store.queue.map((item) => item.id)).toEqual(['tv_track_1']);
    expect(store.queueIndex).toBe(0);
    expect(store.progressRatio).toBe(0.25);
  });

  it('replaces the previous title and queue with mixed native-approved artist artwork hosts', () => {
    const harness = nativeHarness();
    installNativeClientForTest(harness.client);
    const store = useTvPlayerStore();
    const release = store.connect();
    harness.emit('player.snapshot', snapshot);
    const first = { ...track, id: 'artist_first', title: '歌手第一首' };
    const selected = { ...track, id: 'artist_selected', title: '歌手第二首', artworkUrl: 'https://singerimg.kugou.com/uploadpic/400/fixture.jpg' };
    harness.emit('queue.changed', { queue: [first, selected], queueIndex: 1 });
    expect(store.queue.map(item => item.id)).toEqual(['artist_first', 'artist_selected']);
    expect(store.currentTrack?.id).toBe(track.id); // No optimistic metadata.
    harness.emit('player.snapshot', { ...snapshot, currentTrack: selected, queue: [first, selected], queueIndex: 1 });
    expect(store.currentTrack?.title).toBe('歌手第二首');
    expect(store.queueIndex).toBe(1);
    expect(store.queue[1]?.artworkUrl).toBe(selected.artworkUrl);
    // The selected track need not itself have singerimg; every queue item is validated.
    harness.emit('player.snapshot', { ...snapshot, currentTrack: first, queue: [first, selected], queueIndex: 0 });
    expect(store.currentTrack?.title).toBe('歌手第一首');
    release();
  });

  it.each([
    'https://singerimg.kugou.com.evil.test/a.jpg',
    'http://singerimg.kugou.com/a.jpg',
    'https://user@singerimg.kugou.com/a.jpg',
    'https://singerimg.kugou.com:443/a.jpg',
    'https://singerimg.kugou.com/a.jpg?token=secret',
    'https://singerimg.kugou.com/a.jpg?',
    'https://singerimg.kugou.com/a.jpg#',
    'https://imge.kugou.com/a.jpg#fragment',
  ])('keeps artwork authority restrictions for %s', artworkUrl => {
    const store = useTvPlayerStore();
    store.applySnapshot(snapshot);
    expect(store.applySnapshot({ ...snapshot, currentTrack: { ...track, artworkUrl } })).toBe(false);
    expect(store.currentTrack?.artworkUrl).toBe(track.artworkUrl);
  });

  it('merges native deltas and rejects malformed state without corrupting the last snapshot', () => {
    const harness = nativeHarness();
    installNativeClientForTest(harness.client);
    const store = useTvPlayerStore();
    const release = store.connect();

    harness.emit('player.snapshot', snapshot);
    harness.emit('player.position', { positionMs: 2_000, durationMs: 4_000 });
    harness.emit('queue.changed', { queue: [track, { ...track, id: 'tv_track_2', title: '烟火里的尘埃' }], queueIndex: 1 });
    harness.emit('lyrics.changed', { lyrics: [{ startMs: 2_000, endMs: 4_000, text: '下一句' }] });
    harness.emit('app.error', { errorId: 1, code: 'PLAYBACK_RESOLVE_FAILED', message: '当前歌曲暂时无法播放', retryable: true });
    harness.emit('player.position', { positionMs: 'bad', durationMs: 4_000 });

    expect(store.positionMs).toBe(2_000);
    expect(store.queueIndex).toBe(1);
    expect(store.queue).toHaveLength(2);
    expect(store.lyrics[0]?.text).toBe('下一句');
    expect(store.currentTrack?.id).toBe('tv_track_1');
    expect(store.error).toEqual({ errorId: 1, code: 'PLAYBACK_RESOLVE_FAILED', message: '当前歌曲暂时无法播放', retryable: true });
    release();
  });

  it('subscribes once, disconnects cleanly, and sends exact native commands without optimistic mutation', async () => {
    const harness = nativeHarness();
    installNativeClientForTest(harness.client);
    const store = useTvPlayerStore();

    const releaseFirst = store.connect();
    const releaseSecond = store.connect();
    expect(harness.listenerCount('player.snapshot')).toBe(1);

    harness.emit('player.snapshot', snapshot);
    await store.toggle();
    await store.previous();
    await store.next();
    await store.seekBy(10_000);

    expect(store.isPlaying).toBe(true);
    expect(harness.request.mock.calls).toEqual([
      ['player.toggle', {}],
      ['player.previous', {}],
      ['player.next', {}],
      ['player.seekBy', { deltaMs: 10_000 }],
    ]);

    releaseFirst();
    expect(harness.listenerCount('player.snapshot')).toBe(1);
    releaseSecond();
    expect(harness.listenerCount('player.snapshot')).toBe(0);
  });

  it('keeps a fresh resolve error through an older null snapshot and ignores its stale event after native close', async () => {
    const harness = nativeHarness();
    installNativeClientForTest(harness.client);
    const store = useTvPlayerStore();
    const release = store.connect();

    harness.emit('player.snapshot', snapshot);
    harness.emit('app.error', { errorId: 7, code: 'PLAYBACK_RESOLVE_FAILED', message: '当前歌曲暂时无法播放', retryable: true });
    harness.emit('player.snapshot', snapshot);
    expect(store.error?.code).toBe('PLAYBACK_RESOLVE_FAILED');

    harness.request.mockResolvedValueOnce({ accepted: true, errorRevision: 8 });
    await store.clearError();
    harness.emit('app.error', { errorId: 7, code: 'PLAYBACK_RESOLVE_FAILED', message: '当前歌曲暂时无法播放', retryable: true });
    expect(store.error).toBeNull();
    harness.emit('app.error', { errorId: 9, code: 'PLAYBACK_RESOLVE_FAILED', message: '当前歌曲暂时无法播放', retryable: true });
    expect(store.error?.code).toBe('PLAYBACK_RESOLVE_FAILED');
    release();
  });

  it('surfaces a rejected native command without mutating playback state', async () => {
    const request = vi.fn().mockResolvedValue({ accepted: false });
    installNativeClientForTest({ request, on: () => () => undefined });
    const store = useTvPlayerStore();
    store.applySnapshot(snapshot);

    await expect(store.toggle()).rejects.toThrow('播放器暂未就绪');

    expect(store.isPlaying).toBe(true);
    expect(store.commandFailure).toBe('播放器暂未就绪，请稍后重试');
  });

  it('orders snapshot and app errors by native identity and dismisses only through native clear', async () => {
    const harness = nativeHarness();
    harness.request.mockResolvedValueOnce({ accepted: true, errorRevision: 10 });
    installNativeClientForTest(harness.client);
    const store = useTvPlayerStore();
    const release = store.connect();

    harness.emit('player.snapshot', {
      ...snapshot,
      errorRevision: 7,
      error: { errorId: 7, code: 'PLAYBACK_RESOLVE_FAILED', message: '当前歌曲暂时无法播放', retryable: true },
    });
    harness.emit('app.error', { errorId: 9, code: 'PLAYBACK_NEXT_FAILED', message: '下一首暂时无法播放', retryable: true });
    harness.emit('player.snapshot', { ...snapshot, errorRevision: 8, error: null });

    expect(store.error).toEqual({ errorId: 9, code: 'PLAYBACK_NEXT_FAILED', message: '下一首暂时无法播放', retryable: true });
    await store.clearError();
    expect(harness.request).toHaveBeenCalledWith('player.clearError', { errorId: 9 });
    expect(store.error).toBeNull();

    harness.emit('app.error', { errorId: 9, code: 'PLAYBACK_NEXT_FAILED', message: '下一首暂时无法播放', retryable: true });
    expect(store.error).toBeNull();
    harness.emit('app.error', { errorId: 11, code: 'PLAYBACK_PREVIOUS_FAILED', message: '上一首暂时无法播放', retryable: true });
    expect(store.error?.errorId).toBe(11);
    release();
  });

  it('rejects unknown snapshot and command result fields instead of storing them', async () => {
    const request = vi.fn().mockResolvedValue({ accepted: true, diagnostic: 'fixed' });
    installNativeClientForTest({ request, on: () => () => undefined });
    const store = useTvPlayerStore();

    expect(store.applySnapshot({ ...snapshot, diagnostic: 'fixed' })).toBe(false);
    expect(store.currentTrack).toBeNull();
    await expect(store.toggle()).rejects.toThrow('操作失败');
  });
});
