import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest } from '../native/client';
import { useTvBrowseStore } from './browse';
import { useTvAppStore } from './app';

const empty = { newSongs: [], newAlbums: [], newPlaylists: [], artists: [], rankings: [] };
const request = vi.fn();
beforeEach(() => {
  setActivePinia(createPinia());
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  request.mockReset();
  installNativeClientForTest({ request, on: () => () => undefined });
});
afterEach(() => delete window.EchoTvNative);

describe('daily recommendation state', () => {
  const track = { id: 'daily-1', title: 'Daily song', artist: 'Artist', album: '', artwork: '', durationMs: 180000 };
  it('keeps loading distinct from an empty successful result', async () => {
    let resolve!: (value: unknown) => void;
    request.mockImplementation(() => new Promise(done => { resolve = done; }));
    const store = useTvBrowseStore();
    const pending = store.loadDaily();
    expect(store.dailyStatus).toBe('loading');
    resolve({ items: [] });
    await pending;
    expect(store.dailyStatus).toBe('ready');
    expect(store.dailyTracks).toEqual([]);
    expect(store.dailyErrorCode).toBeNull();
  });
  it('retains only a safe diagnostic code and supports retry', async () => {
    const store = useTvBrowseStore();
    request.mockRejectedValue({ code: 'AUTH_EXPIRED', message: 'private upstream details' });
    await store.loadDaily();
    expect(store.dailyErrorCode).toBe('AUTH_EXPIRED');
    request.mockResolvedValue({ items: [track] });
    await store.loadDaily();
    expect(store.dailyStatus).toBe('ready');
    expect(store.dailyErrorCode).toBeNull();
    expect(store.dailyTracks).toEqual([track]);
  });
  it.each([{}, { items: null }, { items: [{ ...track, title: '' }] }])('does not mislabel malformed content as empty %j', async payload => {
    request.mockResolvedValue(payload);
    const store = useTvBrowseStore();
    await store.loadDaily();
    expect(store.dailyStatus).toBe('error');
    expect(store.dailyErrorCode).toBe('CONTENT_INVALID');
  });
  it('never displays arbitrary server text as the diagnostic code', async () => {
    request.mockRejectedValue({ code: 'https://private.invalid/?token=example' });
    const store = useTvBrowseStore();
    await store.loadDaily();
    expect(store.dailyErrorCode).toBe('DAILY_REQUEST_FAILED');
  });
  it('ignores stale daily replies after an account change or a newer retry', async () => {
    const replies: Array<(value: unknown) => void> = [];
    request.mockImplementation(() => new Promise(done => replies.push(done)));
    const store = useTvBrowseStore();
    const first = store.loadDaily();
    useTvAppStore().session = { status: 'loggedIn', displayName: 'new account' };
    const second = store.loadDaily();
    replies[1]({ items: [track] });
    await second;
    replies[0]({ items: [{ ...track, id: 'stale' }] });
    await first;
    expect(store.dailyTracks[0].id).toBe(track.id);
    useTvAppStore().session = { status: 'loggedOut' };
    expect(store.dailyTracks).toEqual([]);
  });
});

describe('explore partial failures', () => {
  it('shares the initial fetch when home prefetch and discovery mount overlap', async () => {
    let resolve!: (payload: unknown) => void;
    request.mockImplementation(() => new Promise(done => { resolve = done; }));
    const store = useTvBrowseStore();
    const home = store.loadExplore();
    const discovery = store.loadExplore();
    expect(request).toHaveBeenCalledTimes(1);
    resolve(empty);
    await Promise.all([home, discovery]);
    expect(store.loaded).toBe(true);
  });
  it('keeps a failed section retryable while healthy sections remain visible', async () => {
    request.mockResolvedValue({ ...empty, rankings: [{ id: 'rank:1', title: 'Fixture', artwork: '' }], failedSections: ['newSongs'] });
    const store = useTvBrowseStore();
    await store.loadExplore();
    expect(store.shelves.find(s => s.id === 'newSongs')?.status).toBe('error');
    expect(store.shelves.find(s => s.id === 'rankings')?.status).toBe('ready');
    expect(store.shelves.find(s => s.id === 'rankings')?.rankings).toHaveLength(1);
  });

  it('does not turn a still-failed retry into ready and hide the section', async () => {
    request.mockResolvedValue({ ...empty, failedSections: ['newSongs'] });
    const store = useTvBrowseStore();
    await store.loadExplore();
    await store.retryShelf('newSongs');
    expect(store.shelves.find(s => s.id === 'newSongs')?.status).toBe('error');
    request.mockResolvedValue(empty);
    await store.retryShelf('newSongs');
    expect(store.shelves.find(s => s.id === 'newSongs')?.status).toBe('ready');
  });

  it('keeps compatibility with the existing response when the new field is absent', async () => {
    request.mockResolvedValue(empty);
    const store = useTvBrowseStore();
    await store.loadExplore();
    expect(store.shelves.every(s => s.status === 'ready')).toBe(true);
  });

  it.each([42, ['not-a-section'], [null]])('does not silently accept malformed section failures %j', async failedSections => {
    request.mockResolvedValue({ ...empty, failedSections });
    const store = useTvBrowseStore();
    await store.loadExplore();
    expect(store.shelves.every(s => s.status === 'error')).toBe(true);
  });
});
