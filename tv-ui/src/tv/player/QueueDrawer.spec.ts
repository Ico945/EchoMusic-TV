import { createPinia, setActivePinia } from 'pinia';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installNativeClientForTest } from '../native/client';
import { createTvRouter } from '../router';
import { useTvPlayerStore } from '../stores/player';
import NowPlayingPage from '../pages/NowPlayingPage.vue';
import { vTvFocus } from '../focus/directive';

enableAutoUnmount(afterEach);

const first = {
  id: 'track-a', title: '第一首', artists: ['歌手 A'], album: '专辑 A', durationMs: 180_000,
  qualities: ['128', '320'], artworkUrl: 'https://imge.kugou.com/stdmusic/400/a.jpg',
};
const second = {
  id: 'track-b', title: '第二首', artists: ['歌手 B'], album: '专辑 B', durationMs: 200_000,
  qualities: ['320', 'flac'], artworkUrl: 'https://imge.kugou.com/stdmusic/400/b.jpg',
};

const resolveError = (errorId = 1) => ({
  errorId,
  code: 'PLAYBACK_RESOLVE_FAILED',
  message: '无法解析播放资源',
  retryable: true,
});

async function mountPlayer(error: object | null = null) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createTvRouter();
  await router.push('/now-playing');
  await router.isReady();
  const store = useTvPlayerStore(pinia);
  store.applySnapshot({
    currentTrack: second, isPlaying: false, positionMs: 1_000, durationMs: second.durationMs,
    queue: [first, second], queueIndex: 1, repeatMode: 'off', quality: 'flac', lyrics: [], error,
    errorRevision: error && 'errorId' in error ? Number(error.errorId) : 0,
  });
  return { store, wrapper: mount(NowPlayingPage, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } }) };
}

describe('Task 5 queue and playback controls', () => {
  const request = vi.fn().mockResolvedValue({ accepted: true });

  beforeEach(() => {
    request.mockReset();
    request.mockImplementation((method: string, payload: { errorId?: number }) => Promise.resolve(
      method === 'player.clearError'
        ? { accepted: true, errorRevision: (payload.errorId ?? 0) + 1 }
        : { accepted: true },
    ));
    installNativeClientForTest({ request, on: () => () => undefined });
  });

  it('returns focus to the queue button after the drawer closes', async () => {
    const { wrapper } = await mountPlayer();
    const queueButton = wrapper.get('[data-focus-id="player-queue"]');
    (queueButton.element as HTMLButtonElement).focus();
    await queueButton.trigger('click');
    await wrapper.get('[data-focus-id="queue-close"]').trigger('click');
    await flushPromises();

    expect(document.activeElement?.getAttribute('data-focus-id')).toBe('player-queue');
  });

  it('uses a stable track id focus target and requests selection without optimistic playback state', async () => {
    const { store, wrapper } = await mountPlayer();
    await wrapper.get('[data-focus-id="player-queue"]').trigger('click');
    expect(wrapper.get('[data-focus-id="queue-track-track-b"]').text()).toContain('第二首');

    await wrapper.get('[data-focus-id="queue-track-track-a"]').trigger('click');
    expect(request).toHaveBeenLastCalledWith('player.selectQueueItem', { trackId: 'track-a' });
    expect(store.queueIndex).toBe(1);
  });

  it('cycles repeat off all one off through native commands only', async () => {
    const { store, wrapper } = await mountPlayer();
    const repeat = wrapper.get('[data-focus-id="player-repeat"]');
    await repeat.trigger('click');
    store.applySnapshot({ currentTrack: second, isPlaying: false, positionMs: 1_000, durationMs: second.durationMs, queue: [first, second], queueIndex: 1, repeatMode: 'all', quality: 'flac', lyrics: [], error: null, errorRevision: 0 });
    await repeat.trigger('click');
    store.applySnapshot({ currentTrack: second, isPlaying: false, positionMs: 1_000, durationMs: second.durationMs, queue: [first, second], queueIndex: 1, repeatMode: 'one', quality: 'flac', lyrics: [], error: null, errorRevision: 0 });
    await repeat.trigger('click');
    store.applySnapshot({ currentTrack: second, isPlaying: false, positionMs: 1_000, durationMs: second.durationMs, queue: [first, second], queueIndex: 1, repeatMode: 'off', quality: 'flac', lyrics: [], error: null, errorRevision: 0 });

    expect(request.mock.calls).toEqual([
      ['player.setRepeatMode', { mode: 'all' }],
      ['player.setRepeatMode', { mode: 'one' }],
      ['player.setRepeatMode', { mode: 'off' }],
    ]);
    expect(store.repeatMode).toBe('off');
  });

  it('queues rapid repeat confirmations without waiting for a native snapshot', async () => {
    const { wrapper } = await mountPlayer();
    const repeat = wrapper.get('[data-focus-id="player-repeat"]');

    await repeat.trigger('click');
    await repeat.trigger('click');
    await repeat.trigger('click');

    expect(request.mock.calls).toEqual([
      ['player.setRepeatMode', { mode: 'all' }],
      ['player.setRepeatMode', { mode: 'one' }],
      ['player.setRepeatMode', { mode: 'off' }],
    ]);
  });

  it('keeps the newest pending repeat intent when an older request rejects out of order', async () => {
    let rejectAll!: (reason?: unknown) => void;
    request
      .mockImplementationOnce(() => new Promise<{ accepted: boolean }>((_resolve, reject) => { rejectAll = reject; }))
      .mockResolvedValueOnce({ accepted: true });
    const { wrapper } = await mountPlayer();
    const repeat = wrapper.get('[data-focus-id="player-repeat"]');

    await repeat.trigger('click');
    await repeat.trigger('click');
    await flushPromises();
    rejectAll(new Error('all rejected after one was requested'));
    await flushPromises();
    await repeat.trigger('click');

    expect(request.mock.calls).toEqual([
      ['player.setRepeatMode', { mode: 'all' }],
      ['player.setRepeatMode', { mode: 'one' }],
      ['player.setRepeatMode', { mode: 'off' }],
    ]);
  });

  it('lists only qualities supplied by the native current track', async () => {
    const { wrapper } = await mountPlayer();
    await wrapper.get('[data-focus-id="player-quality"]').trigger('click');

    expect(wrapper.find('[data-focus-id="quality-128"]').exists()).toBe(false);
    await wrapper.get('[data-focus-id="quality-flac"]').trigger('click');
    expect(request).not.toHaveBeenCalled();
    await wrapper.get('[data-focus-id="quality-320"]').trigger('click');
    expect(request).toHaveBeenLastCalledWith('player.setQuality', { quality: '320' });
    expect(wrapper.find('[aria-label="音质选择"]').exists()).toBe(true);
  });

  it('guards duplicate quality requests and keeps failure retry inside the drawer', async () => {
    let reject!: (error: Error) => void;
    request.mockReturnValueOnce(new Promise((_done, fail) => { reject = fail; }));
    const { wrapper, store } = await mountPlayer();
    await wrapper.get('[data-focus-id="player-quality"]').trigger('click');
    const choice = wrapper.get('[data-focus-id="quality-320"]');
    await choice.trigger('click'); await choice.trigger('click');
    expect(request).toHaveBeenCalledTimes(1); expect(choice.attributes('aria-busy')).toBe('true');
    expect(store.quality).toBe('flac');
    reject(new Error('offline')); await flushPromises();
    expect(wrapper.text()).toContain('音质切换失败');
    await choice.trigger('click'); await flushPromises();
    expect(request).toHaveBeenCalledTimes(2); expect(store.quality).toBe('flac');
  });

  it('does not label a new track with the previous track quality request result', async () => {
    let done!: (value: unknown) => void;
    request.mockReturnValueOnce(new Promise(resolve => { done = resolve; }));
    const { wrapper, store } = await mountPlayer();
    await wrapper.get('[data-focus-id="player-quality"]').trigger('click');
    await wrapper.get('[data-focus-id="quality-320"]').trigger('click');
    store.applySnapshot({ currentTrack:first,isPlaying:false,positionMs:0,durationMs:first.durationMs,queue:[first],queueIndex:0,repeatMode:'off',quality:'128',lyrics:[],error:null,errorRevision:0 });
    await flushPromises(); done({ accepted:true }); await flushPromises();
    expect(wrapper.find('[role=status]').exists()).toBe(false);
    expect(wrapper.get('[data-focus-id="quality-128"]').attributes('aria-pressed')).toBe('true');
  });

  it('offers exactly retry current skip next and close for resolve failures', async () => {
    const { store, wrapper } = await mountPlayer(resolveError());
    const actions = wrapper.findAll('[data-playback-error-action]').map((button) => button.attributes('data-playback-error-action'));
    expect(actions).toEqual(['retry', 'skip', 'close']);

    await wrapper.get('[data-focus-id="playback-error-retry"]').trigger('click');
    await flushPromises();
    store.applySnapshot({ currentTrack: second, isPlaying: false, positionMs: 1_000, durationMs: second.durationMs, queue: [first, second], queueIndex: 1, repeatMode: 'off', quality: 'flac', lyrics: [], error: resolveError(3), errorRevision: 3 });
    await flushPromises();
    await wrapper.get('[data-focus-id="playback-error-skip"]').trigger('click');
    await flushPromises();
    store.applySnapshot({ currentTrack: second, isPlaying: false, positionMs: 1_000, durationMs: second.durationMs, queue: [first, second], queueIndex: 1, repeatMode: 'off', quality: 'flac', lyrics: [], error: resolveError(5), errorRevision: 5 });
    await flushPromises();
    await wrapper.get('[data-focus-id="playback-error-close"]').trigger('click');
    await flushPromises();
    expect(request.mock.calls).toEqual([
      ['player.retryCurrent', {}],
      ['player.clearError', { errorId: 1 }],
      ['player.next', {}],
      ['player.clearError', { errorId: 3 }],
      ['player.clearError', { errorId: 5 }],
    ]);
    expect(store.isPlaying).toBe(false);
  });

  it('retries natively without truncating a 201-track queue projection', async () => {
    const { store, wrapper } = await mountPlayer(resolveError());
    const queue = Array.from({ length: 201 }, (_, index) => ({ ...first, id: `queue-${index}`, title: `歌曲 ${index}` }));
    store.applySnapshot({
      currentTrack: queue[200], isPlaying: false, positionMs: 0, durationMs: first.durationMs,
      queue, queueIndex: 200, repeatMode: 'off', quality: '320', lyrics: [],
      error: resolveError(2),
      errorRevision: 2,
    });
    await flushPromises();

    await wrapper.get('[data-focus-id="playback-error-retry"]').trigger('click');
    await flushPromises();
    expect(request.mock.calls.slice(-2)).toEqual([
      ['player.retryCurrent', {}],
      ['player.clearError', { errorId: 2 }],
    ]);
  });

  it.each([
    ['PLAYBACK_NEXT_FAILED', 'player.next', '重试下一首'],
    ['PLAYBACK_PREVIOUS_FAILED', 'player.previous', '重试上一首'],
  ])('replays %s with the matching native command', async (code, method, label) => {
    const { wrapper } = await mountPlayer({ errorId: 1, code, message: '切歌失败', retryable: true });

    expect(wrapper.get('[data-focus-id="playback-error-retry"]').text()).toBe(label);
    await wrapper.get('[data-focus-id="playback-error-retry"]').trigger('click');
    await flushPromises();

    expect(request.mock.calls).toEqual([
      [method, {}],
      ['player.clearError', { errorId: 1 }],
    ]);
  });

  it('sends fixed local sound effects and surfaces an unavailable preset', async () => {
    request.mockResolvedValueOnce({ accepted: true }).mockResolvedValueOnce({ accepted: false });
    const { wrapper } = await mountPlayer();
    await wrapper.get('[data-focus-id="player-sound-effect"]').trigger('click');
    expect(wrapper.findAll('[data-sound-effect-preset]').map((row) => row.attributes('data-sound-effect-preset')))
      .toEqual(['off', 'bass', 'vocal', 'live']);

    await wrapper.get('[data-focus-id="sound-effect-bass"]').trigger('click');
    await flushPromises();
    await wrapper.get('[data-focus-id="sound-effect-vocal"]').trigger('click');
    await flushPromises();
    expect(request.mock.calls).toEqual([
      ['player.setSoundEffect', { preset: 'bass' }],
      ['player.setSoundEffect', { preset: 'vocal' }],
    ]);
    expect(wrapper.get('[data-sound-effect-error]').text()).toContain('音效未应用');
  });
});
