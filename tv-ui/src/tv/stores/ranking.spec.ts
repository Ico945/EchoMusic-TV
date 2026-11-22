import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest } from '../native/client';
import { useTvBrowseStore } from './browse';
const request = vi.fn();
const track = (id: string) => ({ id, title: id, artist: 'Artist', album: '', artwork: '', durationMs: 180000 });
const page = (id: string, nextCursor: string | null = null) => ({ items: [track(id)], total: 90, nextCursor });
beforeEach(() => {
  setActivePinia(createPinia()); request.mockReset();
  installNativeClientForTest({ request, on: () => () => undefined });
});
describe('ranking state', () => {
  it('only the current chart may publish a pending response', async () => {
    const replies: Array<(payload: unknown) => void> = [];
    request.mockImplementation(() => new Promise(resolve => replies.push(resolve)));
    const store = useTvBrowseStore();
    const a = store.loadRanking('a'); const b = store.loadRanking('b');
    replies[1](page('b-song')); await b;
    replies[0](page('a-song')); await a;
    expect(store.rankingId).toBe('b');
    expect(store.rankingTracks.map(item => item.id)).toEqual(['b-song']);
  });
  it('clears old chart content and pagination before showing a new chart', async () => {
    const store = useTvBrowseStore(); request.mockResolvedValue(page('a-song', '2')); await store.loadRanking('a');
    let finish!: (payload: unknown) => void;
    request.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const pending = store.loadRanking('b');
    expect(store.rankingTracks).toEqual([]); expect(store.rankingNextCursor).toBeNull();
    finish(page('b-song')); await pending;
  });
  it('deduplicates concurrent pagination and overlapping track ids', async () => {
    const store = useTvBrowseStore(); request.mockResolvedValue(page('song', '2')); await store.loadRanking('a');
    let finish!: (payload: unknown) => void;
    request.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const first = store.loadRanking('a', '2'); await store.loadRanking('a', '2');
    expect(request).toHaveBeenCalledTimes(2);
    finish(page('song')); await first;
    expect(store.rankingTracks).toHaveLength(1);
  });
  it('malformed first-page payload is an error while later failures retain playable rows', async () => {
    const store = useTvBrowseStore(); request.mockResolvedValue({}); await store.loadRanking('a');
    expect(store.rankingStatus).toBe('error');
    request.mockResolvedValue(page('song', '2')); await store.loadRanking('a');
    request.mockRejectedValue(new Error('offline')); await store.loadRanking('a', '2');
    expect(store.rankingStatus).toBe('error'); expect(store.rankingTracks).toHaveLength(1);
    expect(store.rankingNextCursor).toBe('2');
  });
});
