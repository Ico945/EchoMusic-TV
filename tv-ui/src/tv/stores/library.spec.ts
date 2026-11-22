import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { useTvAppStore } from './app';
import { useTvLibraryStore } from './library';

const card = (id: string) => ({ id, title: '歌单 ' + id, kind: 'playlist', subtitle: '', artwork: '' });
const page = (ids: string[], nextCursor: string | null = null) => ({ items: ids.map(card), nextCursor, total: null });
const request = vi.fn();
beforeEach(() => {
  setActivePinia(createPinia());
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  request.mockReset();
  installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });
  useTvAppStore().session = { status: 'loggedIn', displayName: 'fixture' };
});
afterEach(() => delete window.EchoTvNative);

describe('library request ownership and filtered pagination', () => {
  it.each([true, false])('accepts song mutation %s only for its original opaque identity', async favorite => {
    request.mockResolvedValue({ kind: 'song', id: 'tv_r50', favorite });
    expect(await useTvLibraryStore().favorite('song', 'tv_r50', favorite)).toBe(true);
    request.mockResolvedValue({ kind: 'song', id: '99881122', favorite });
    expect(await useTvLibraryStore().favorite('song', 'tv_r50', favorite)).toBe(false);
  });
  it('restores the removed song when a delete fails, and removes it after confirmed retry', async () => {
    request.mockResolvedValue({ items: [{ ...card('tv_r50'), kind: 'song' }], nextCursor: null, total: 1 });
    const store = useTvLibraryStore(); await store.loadSection('favoriteSongs');
    request.mockRejectedValueOnce({ code: 'CONTENT_UNAVAILABLE' });
    expect(await store.favorite('song', 'tv_r50', false)).toBe(false);
    expect(store.sections.favoriteSongs.items.map(c => c.id)).toEqual(['tv_r50']);
    request.mockResolvedValue({ kind: 'song', id: 'tv_r50', favorite: false });
    expect(await store.favorite('song', 'tv_r50', false)).toBe(true);
    expect(store.sections.favoriteSongs.items).toEqual([]);
  });
  it('reads the unfiltered inventory via its read-only method', async () => {
    request.mockResolvedValue(page(['own', 'collected', 'unclassified']));
    const store = useTvLibraryStore();
    await store.loadSection('allPlaylists');
    expect(request).toHaveBeenCalledWith('library.playlists', {});
    expect(store.sections.allPlaylists.items).toHaveLength(3);
  });
  it('keeps per-page counters without double counting retries or exposing unknown fields', async () => {
    const info = { page: 1, received: 30, playlists: 2, created: 1, collected: 1, albums: 28, missingCreator: 0 };
    request.mockResolvedValueOnce({ ...page(['a'], '2'), librarySync: { ...info, privateField: 'must-not-store' } })
      .mockRejectedValueOnce({ code: 'NETWORK_TIMEOUT' })
      .mockResolvedValueOnce({ ...page(['b']), librarySync: { ...info, page: 2, received: 1 } });
    const store = useTvLibraryStore();
    await store.loadSection('allPlaylists');
    await store.loadSection('allPlaylists', '2');
    await store.loadSection('allPlaylists', '2');
    expect(Object.keys(store.sections.allPlaylists.syncPages!)).toEqual(['1', '2']);
    expect(JSON.stringify(store.sections.allPlaylists.syncPages)).not.toContain('privateField');
  });
  it('distinguishes UI validation failure from successful empty results', async () => {
    request.mockResolvedValue({ items: [{ ...card('a'), title: '' }] });
    const store = useTvLibraryStore();
    await store.loadSection('allPlaylists');
    expect(store.sections.allPlaylists.errorCode).toBe('LIBRARY_UI_PAYLOAD');
    expect(store.sections.allPlaylists.status).toBe('error');
  });
  it('continues through empty mixed pages until a collected playlist appears', async () => {
    request.mockResolvedValueOnce(page([], '2')).mockResolvedValueOnce(page([], '3')).mockResolvedValueOnce(page(['p1']));
    const store = useTvLibraryStore();
    await store.loadSection('favoritePlaylists');
    expect(request).toHaveBeenNthCalledWith(3, 'library.section', { section: 'favoritePlaylists', cursor: '3' });
    expect(store.sections.favoritePlaylists.items.map(c => c.id)).toEqual(['p1']);
    expect(store.sections.favoritePlaylists.status).toBe('ready');
  });
  it('bounds empty-page scanning and retains a load-more cursor', async () => {
    request.mockImplementation((_method, args) => Promise.resolve(page([], String(Number(args.cursor ?? 1) + 1))));
    const store = useTvLibraryStore();
    await store.loadSection('favoritePlaylists');
    expect(request).toHaveBeenCalledTimes(5);
    expect(store.sections.favoritePlaylists.nextCursor).toBe('6');
  });
  it('does not convert a network failure to an empty success', async () => {
    request.mockRejectedValue(new Error('offline'));
    const store = useTvLibraryStore();
    await store.loadSection('favoritePlaylists');
    expect(store.sections.favoritePlaylists.status).toBe('error');
  });
  it('preserves existing cards and cursor when a later page fails, then deduplicates retry', async () => {
    request.mockResolvedValueOnce(page(['a'], '2')).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(page(['a', 'b']));
    const store = useTvLibraryStore();
    await store.loadSection('favoritePlaylists');
    await store.loadSection('favoritePlaylists', '2');
    expect(store.sections.favoritePlaylists.items).toHaveLength(1);
    expect(store.sections.favoritePlaylists.nextCursor).toBe('2');
    await store.loadSection('favoritePlaylists', '2');
    expect(store.sections.favoritePlaylists.items.map(c => c.id)).toEqual(['a', 'b']);
  });
  it('invalidates a pending old-account response on logout', async () => {
    let resolve!: (value: unknown) => void;
    request.mockImplementationOnce(() => new Promise(r => { resolve = r; }));
    const store = useTvLibraryStore();
    const pending = store.loadSection('favoritePlaylists');
    useTvAppStore().session = { status: 'loggedOut' };
    resolve(page(['private-old-account']));
    await pending;
    expect(store.sections).toEqual({});
  });
  it('retains state when another snapshot confirms the same signed-in session', async () => {
    request.mockResolvedValue(page(['a']));
    const store = useTvLibraryStore();
    await store.loadSection('favoritePlaylists');
    useTvAppStore().session = { status: 'loggedIn', displayName: 'fixture' };
    expect(store.sections.favoritePlaylists.items[0].id).toBe('a');
  });
  it('preserves the auth-expired code for a directly reachable QR action', async () => {
    request.mockRejectedValue({ code: 'AUTH_EXPIRED' });
    const store = useTvLibraryStore();
    await store.loadSection('favoritePlaylists');
    expect(store.sections.favoritePlaylists.errorCode).toBe('AUTH_EXPIRED');
  });
  it('deduplicates repeated confirm while a request is pending', async () => {
    let resolve!: (value: unknown) => void;
    request.mockImplementationOnce(() => new Promise(r => { resolve = r; }));
    const store = useTvLibraryStore();
    const pending = store.loadSection('favoritePlaylists');
    await store.loadSection('favoritePlaylists');
    expect(request).toHaveBeenCalledTimes(1);
    resolve(page([]));
    await pending;
  });
});
