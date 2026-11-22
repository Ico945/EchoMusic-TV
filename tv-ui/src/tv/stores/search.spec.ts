import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest } from '../native/client';
import { useTvSearchStore } from './search';

const request = vi.fn();
const card = (id: string) => ({ id, title: id, kind: 'song', subtitle: 'Artist', artwork: '' });
const page = (id: string, category = 'song', nextCursor: string | null = null) => ({ category, items: [card(id)], total: 90, nextCursor });
beforeEach(() => {
  setActivePinia(createPinia());
  localStorage.clear();
  request.mockReset();
  installNativeClientForTest({ request, on: () => () => undefined });
});

describe('TV search request ownership', () => {
  it('distinguishes a native authentication failure from empty search results', async () => {
    const store = useTvSearchStore(); store.query = 'music';
    request.mockRejectedValue({ code: 'AUTH_EXPIRED' }); await store.submit();
    expect(store.error).toBe(true); expect(store.requiresLogin).toBe(true);
    request.mockResolvedValue(page('song')); await store.retry();
    expect(store.requiresLogin).toBe(false); expect(store.error).toBe(false);
  });

  it('does not submit whitespace and uses real song ids for focus', async () => {
    const store = useTvSearchStore();
    store.query = '  ';
    await store.submit();
    expect(store.submitted).toBe(false);
    expect(request).not.toHaveBeenCalled();
    request.mockResolvedValue(page('song-37'));
    store.query = ' song ';
    await store.submit();
    expect(store.firstResultFocusId).toBe('search-song-song-37');
    expect(store.submittedQuery).toBe('song');
  });

  it('newer queries own data, loading and history even when responses arrive out of order', async () => {
    const replies: Array<(payload: unknown) => void> = [];
    request.mockImplementation(() => new Promise(resolve => replies.push(resolve)));
    const store = useTvSearchStore();
    store.query = 'old'; const first = store.submit();
    store.query = 'new'; const second = store.submit();
    replies[0](page('old')); await first;
    expect(store.loading).toBe(true);
    expect(store.results.items).toEqual([]);
    replies[1](page('new')); await second;
    expect(store.results.items.map(item => item.id)).toEqual(['new']);
    expect(store.history).toEqual(['new']);
  });

  it('category changes clear incompatible rows and ignore previous category replies', async () => {
    const replies: Array<(payload: unknown) => void> = [];
    request.mockImplementation(() => new Promise(resolve => replies.push(resolve)));
    const store = useTvSearchStore();
    store.query = 'music'; const first = store.submit();
    const second = store.setCategory('album');
    replies[1]({ ...page('album:1', 'album'), items: [{ ...card('album:1'), kind: 'album' }] }); await second;
    replies[0](page('song')); await first;
    expect(store.category).toBe('album');
    expect(store.firstResultFocusId).toBe('search-album-0');
    expect(store.results.items[0].kind).toBe('album');
  });

  it('pagination and category changes use submitted text, not the edited draft', async () => {
    const store = useTvSearchStore();
    request.mockResolvedValue(page('first', 'song', '2'));
    store.query = 'original'; await store.submit();
    store.query = 'draft';
    request.mockResolvedValue(page('second')); await store.searchNext();
    expect(request).toHaveBeenLastCalledWith('content.search', { query: 'original', category: 'song', cursor: '2' });
    expect(store.results.items.map(item => item.id)).toEqual(['first', 'second']);
    request.mockResolvedValue({ category: 'album', items: [], total: 0, nextCursor: null });
    await store.setCategory('album');
    expect(request).toHaveBeenLastCalledWith('content.search', { query: 'original', category: 'album' });
  });

  it('retrying a failed next page retains existing rows and retries the same cursor', async () => {
    const store = useTvSearchStore(); store.query = 'music';
    request.mockResolvedValue(page('first', 'song', '2')); await store.submit();
    request.mockRejectedValue(new Error('offline')); await store.searchNext();
    expect(store.results.items.map(item => item.id)).toEqual(['first']);
    request.mockResolvedValue(page('second')); await store.retry();
    expect(request).toHaveBeenLastCalledWith('content.search', { query: 'music', category: 'song', cursor: '2' });
    expect(store.results.items.map(item => item.id)).toEqual(['first', 'second']);
  });

  it('returning to suggestions invalidates pending results without deleting the draft', async () => {
    let finish!: (payload: unknown) => void;
    request.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const store = useTvSearchStore(); store.query = 'music';
    const pending = store.submit(); store.showSuggestions();
    finish(page('stale')); await pending;
    expect(store.query).toBe('music');
    expect(store.submitted).toBe(false);
    expect(store.results.items).toEqual([]);
    expect(store.history).toEqual([]);
  });

  it('treats category mismatches as retryable errors, not successful empty content', async () => {
    const store = useTvSearchStore(); store.query = 'music';
    request.mockResolvedValue(page('wrong', 'album')); await store.submit();
    expect(store.error).toBe(true);
    expect(store.loading).toBe(false);
  });

  it('lyric matches use song-row focus and pagination merges do not duplicate ids', async () => {
    const store = useTvSearchStore(); store.query = 'line';
    await store.setCategory('lyric');
    request.mockResolvedValue(page('song-8', 'lyric', '2')); await store.submit();
    expect(store.isSongCategory).toBe(true);
    expect(store.firstResultFocusId).toBe('search-lyric-song-8');
    request.mockResolvedValue(page('song-8', 'lyric')); await store.searchNext();
    expect(store.results.items).toHaveLength(1);
  });
});
