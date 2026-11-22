import { describe, expect, it } from 'vitest';
import {
  isContentKind,
  isSearchCategory,
  parseTvContentCard,
  parseTvPagedResult,
  parseTvLibrarySummary,
  parseTvComment,
  parseTvContentDetail,
} from './contentTypes';

const card = (overrides: Record<string, unknown> = {}) => ({
  id: 'playlist:42',
  title: '夜晚歌单',
  kind: 'playlist',
  subtitle: '12 首',
  artwork: '',
  ...overrides,
});

describe('contentTypes', () => {
  it('preserves a separate personal-playlist navigation id without replacing its mutation id', () => {
    const parsed = parseTvContentCard(card({ id: '300', detailId: 'playlist:library_300' }));
    expect(parsed.id).toBe('300');
    expect(parsed.detailId).toBe('playlist:library_300');
    expect(parseTvContentCard(card()).detailId).toBeUndefined();
  });
  it('accepts every fixed search category and rejects unknown values', () => {
    expect(['song', 'playlist', 'album', 'artist', 'lyric', 'mv'].every(isSearchCategory)).toBe(true);
    expect(isSearchCategory('user')).toBe(false);
    expect(isSearchCategory('')).toBe(false);
    expect(isSearchCategory(undefined)).toBe(false);
  });

  it('accepts every fixed content kind and rejects unknown values', () => {
    expect(['song', 'playlist', 'album', 'artist', 'mv', 'user'].every(isContentKind)).toBe(true);
    expect(isContentKind('radio')).toBe(false);
  });

  it('parses a content card and rejects missing fields', () => {
    const parsed = parseTvContentCard(card());
    expect(parsed.id).toBe('playlist:42');
    expect(parsed.kind).toBe('playlist');
    expect(() => parseTvContentCard(card({ id: undefined }))).toThrow();
    expect(() => parseTvContentCard(card({ title: undefined }))).toThrow();
    expect(() => parseTvContentCard(card({ kind: 'radio' }))).toThrow();
    expect(() => parseTvContentCard(null)).toThrow();
  });

  it('parses a paged result and preserves the cursor', () => {
    const page = parseTvPagedResult(
      { items: [card(), card({ id: 'playlist:43' })], nextCursor: 'p2', total: 90 },
      parseTvContentCard,
    );
    expect(page.items.map((item) => item.id)).toEqual(['playlist:42', 'playlist:43']);
    expect(page.nextCursor).toBe('p2');
    expect(page.total).toBe(90);
    expect(() => parseTvPagedResult({ items: [card()] }, parseTvContentCard)).not.toThrow();
    expect(() => parseTvPagedResult({ items: 'nope' }, parseTvContentCard)).toThrow();
    expect(() => parseTvPagedResult({ items: [card({ kind: 'radio' })] }, parseTvContentCard)).toThrow();
  });

  it('parses a library summary with camelCase section keys', () => {
    const summary = parseTvLibrarySummary({
      counts: { favoriteSongs: 3, history: 12, favoritePlaylists: 2 },
      featured: [card({ id: 'song:1', kind: 'song' })],
    });
    expect(summary.counts.favoriteSongs).toBe(3);
    expect(summary.counts.history).toBe(12);
    expect(summary.counts.favoritePlaylists).toBe(2);
    expect(summary.featured).toHaveLength(1);
    expect(() => parseTvLibrarySummary({ counts: { favoriteSongs: 'x' } })).toThrow();
  });

  it('parses comments and rejects malformed reply pages', () => {
    const parsed = parseTvComment({
      id: 'c1',
      user: { id: 'u1', nickname: 'nick', avatar: '', summary: '' },
      content: '不错',
      time: 1700000000000,
      likeCount: 3,
      replies: null,
    });
    expect(parsed.user.nickname).toBe('nick');
    expect(parsed.likeCount).toBe(3);
    expect(() => parseTvComment({ id: 'c2' })).toThrow();
    expect(() =>
      parseTvComment({
        id: 'c3',
        user: { id: 'u1', nickname: 'nick' },
        content: 'x',
        time: 1,
        likeCount: 0,
        replies: { items: [card({ kind: 'radio' })] },
      }),
    ).toThrow();
  });

  it('parses each detail variant with its stable tabs', () => {
    const playlist = parseTvContentDetail({
      id: 'playlist:1',
      title: 'p',
      kind: 'playlist',
      artwork: '',
      creator: null,
      playCount: 0,
      tags: [],
      introduction: '',
      trackCount: null,
      tabs: ['songs', 'comments', 'introduction'],
      tracks: { items: [], nextCursor: null, total: null },
    });
    expect(playlist.kind).toBe('playlist');
    expect(playlist.tabs).toEqual(['songs', 'comments', 'introduction']);

    const artist = parseTvContentDetail({
      id: 'artist:9',
      title: 'a',
      kind: 'artist',
      artwork: '',
      summary: '',
      tabs: ['songs', 'albums', 'mv'],
      hotTracks: [],
      children: { items: [], nextCursor: null, total: null },
    });
    expect(artist.kind).toBe('artist');
    expect(artist.tabs).toEqual(['songs', 'albums', 'mv']);

    expect(() => parseTvContentDetail({ id: 'x', title: 'x', kind: 'radio' })).toThrow();
  });
});
