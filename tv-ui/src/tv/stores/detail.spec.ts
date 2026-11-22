import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { useTvDetailStore } from './detail';

const artist = (id = '1') => ({ id, kind: 'artist', title: 'Artist', artwork: '', summary: '', tabs: ['songs', 'albums', 'mv'],
  hotTracks: [], children: { items: [], nextCursor: null, total: 0 } });
const page = (cursor: string | null = null) => ({ items: [], nextCursor: cursor, total: 0 });
function deferred() {
  let resolve!: (value: unknown) => void; let reject!: (error: Error) => void;
  const promise = new Promise<unknown>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}
beforeEach(() => setActivePinia(createPinia()));
describe('Detail child request ownership', () => {
  it.each(['success', 'failure'] as const)('releases an obsolete loading lock and ignores its late %s', async outcome => {
    const old = deferred(); const newer = deferred();
    const request = vi.fn().mockResolvedValueOnce(artist()).mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(artist('2')).mockResolvedValueOnce(artist()).mockReturnValueOnce(newer.promise);
    installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });
    const store = useTvDetailStore(); await store.loadDetail('artist', 'artist:1');
    const obsolete = store.loadChildren('artist', 'artist:1', 'mv');
    await store.loadDetail('artist', 'artist:2'); await store.loadDetail('artist', 'artist:1');
    const latest = store.loadChildren('artist', 'artist:1', 'mv');
    expect(request).toHaveBeenCalledTimes(5);
    if (outcome === 'success') old.resolve(page('stale')); else old.reject(new Error('old failure'));
    await obsolete; expect(store.childrenStatus['artist:1:mv']).toBe('loading');
    newer.resolve(page('current')); await latest;
    expect(store.childrenStatus['artist:1:mv']).toBe('ready'); expect(store.children['artist:1:mv'].nextCursor).toBe('current');
  });
  it('still deduplicates the same tab request within the active detail', async () => {
    const pending = deferred(); const request = vi.fn().mockResolvedValueOnce(artist()).mockReturnValue(pending.promise);
    installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });
    const store = useTvDetailStore(); await store.loadDetail('artist', '1');
    const first = store.loadChildren('artist', '1', 'mv'); await store.loadChildren('artist', 'artist:1', 'mv');
    expect(request).toHaveBeenCalledTimes(2); pending.resolve(page()); await first;
  });
});
