import { TvPayloadValidationError, parseTvTrack } from '../stores/parsers';
import type { TvTrack } from '../stores/app';

export const SEARCH_CATEGORIES = ['song', 'playlist', 'album', 'artist', 'lyric', 'mv'] as const;
export type SearchCategory = (typeof SEARCH_CATEGORIES)[number];

export const CONTENT_KINDS = ['song', 'playlist', 'album', 'artist', 'mv', 'user'] as const;
export type ContentKind = (typeof CONTENT_KINDS)[number];

export function isSearchCategory(value: unknown): value is SearchCategory {
  return typeof value === 'string' && (SEARCH_CATEGORIES as readonly string[]).includes(value);
}

export function isContentKind(value: unknown): value is ContentKind {
  return typeof value === 'string' && (CONTENT_KINDS as readonly string[]).includes(value);
}

type JsonRecord = Record<string, unknown>;

function record(value: unknown, label: string): JsonRecord {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new TvPayloadValidationError(`${label} must be an object`);
  }
  return value as JsonRecord;
}

function text(value: unknown, label: string, nonEmpty = false): string {
  if (typeof value !== 'string' || (nonEmpty && value.trim().length === 0)) {
    throw new TvPayloadValidationError(`${label} must be${nonEmpty ? ' a non-empty' : ' a'} string`);
  }
  return value;
}

function list(value: unknown, label: string): unknown[] {
  if (!Array.isArray(value)) throw new TvPayloadValidationError(`${label} must be an array`);
  return value;
}

function count(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new TvPayloadValidationError(`${label} must be a finite non-negative number`);
  }
  return value;
}

function optional(value: unknown): boolean {
  return value === undefined || value === null;
}

export type TvContentCard = {
  id: string;
  title: string;
  kind: ContentKind;
  subtitle: string;
  artwork: string;
  badge?: string;
  detailId?: string;
};

export function parseTvContentCard(value: unknown): TvContentCard {
  const card = record(value, 'Content card');
  const id = text(card.id, 'Content card id', true);
  if (id.length > 160) throw new TvPayloadValidationError('Content card id is unsupported');
  const kind = card.kind;
  if (!isContentKind(kind)) throw new TvPayloadValidationError('Content card kind is unsupported');
  return {
    id,
    title: text(card.title, 'Content card title', true),
    kind,
    subtitle: text(card.subtitle, 'Content card subtitle'),
    artwork: text(card.artwork, 'Content card artwork'),
    ...(card.badge === undefined ? {} : { badge: text(card.badge, 'Content card badge', true) }),
    ...(card.detailId === undefined ? {} : { detailId: text(card.detailId, 'Content card detail id', true) }),
  };
}

export type TvPagedResult<T> = {
  items: T[];
  nextCursor: string | null;
  total: number | null;
};

export type LibrarySyncInfo = {
  page: number; received: number; playlists: number; created: number;
  collected: number; albums: number; missingCreator: number;
};

export function parseLibrarySyncInfo(value: unknown): LibrarySyncInfo | undefined {
  if (optional(value)) return undefined;
  const raw = record(value, 'Library sync');
  const result = {} as LibrarySyncInfo;
  for (const key of ['page', 'received', 'playlists', 'created', 'collected', 'albums', 'missingCreator'] as const) {
    const number = count(raw[key], 'Library sync ' + key);
    if (!Number.isSafeInteger(number) || (key === 'page' && number < 1)) throw new TvPayloadValidationError('Library sync count is invalid');
    result[key] = number;
  }
  return result;
}

export function parseTvPagedResult<T>(value: unknown, parseItem: (item: unknown) => T): TvPagedResult<T> {
  const page = record(value, 'Paged result');
  const nextCursor = page.nextCursor;
  const total = page.total;
  return {
    items: list(page.items, 'Paged result items').map((item, index) => parseItemWithLabel(parseItem, item, index)),
    nextCursor: optional(nextCursor) ? null : text(nextCursor, 'Paged result cursor', true),
    total: optional(total) ? null : count(total, 'Paged result total'),
  };
}

function parseItemWithLabel<T>(parseItem: (item: unknown) => T, item: unknown, index: number): T {
  try {
    return parseItem(item);
  } catch (error) {
    if (error instanceof TvPayloadValidationError) {
      throw new TvPayloadValidationError(`items[${index}] is invalid: ${error.message}`);
    }
    throw error;
  }
}

export type TvUserProfile = {
  id: string;
  nickname: string;
  avatar: string;
  summary: string;
};

export function parseTvUserProfile(value: unknown): TvUserProfile {
  const profile = record(value, 'User profile');
  return {
    id: text(profile.id, 'User profile id', true),
    nickname: text(profile.nickname, 'User profile nickname', true),
    avatar: text(profile.avatar, 'User profile avatar'),
    summary: text(profile.summary, 'User profile summary'),
  };
}

export type TvComment = {
  id: string;
  user: TvUserProfile;
  content: string;
  time: number;
  likeCount: number;
  replies: TvPagedResult<TvComment> | null;
};

export function parseTvComment(value: unknown): TvComment {
  const comment = record(value, 'Comment');
  const replies = comment.replies;
  return {
    id: text(comment.id, 'Comment id', true),
    user: parseTvUserProfile(comment.user),
    content: text(comment.content, 'Comment content'),
    time: count(comment.time, 'Comment time'),
    likeCount: count(comment.likeCount, 'Comment likeCount'),
    replies: optional(replies) ? null : parseTvPagedResult(replies, parseTvComment),
  };
}

export type TvRankingSummary = {
  id: string;
  title: string;
  artwork: string;
};

export function parseTvRanking(value: unknown): TvRankingSummary {
  const ranking = record(value, 'Ranking');
  return {
    id: text(ranking.id, 'Ranking id', true),
    title: text(ranking.title, 'Ranking title', true),
    artwork: text(ranking.artwork, 'Ranking artwork'),
  };
}

export const LIBRARY_SECTION_KEYS = [
  'favoriteSongs',
  'favoriteArtists',
  'favoriteUsers',
  'favoriteAlbums',
  'favoriteMvs',
  'history',
  'cloud',
  'purchasedSongs',
  'purchasedAlbums',
  'createdPlaylists',
  'favoritePlaylists',
] as const;
export type LibrarySectionKey = (typeof LIBRARY_SECTION_KEYS)[number];

export type TvLibrarySummary = {
  counts: Partial<Record<LibrarySectionKey, number>>;
  featured: TvContentCard[];
};

export function parseTvLibrarySummary(value: unknown): TvLibrarySummary {
  const summary = record(value, 'Library summary');
  const countsValue = record(summary.counts, 'Library counts');
  const counts: Partial<Record<LibrarySectionKey, number>> = {};
  for (const key of LIBRARY_SECTION_KEYS) {
    const raw = countsValue[key];
    if (raw === undefined) continue;
    counts[key] = count(raw, `Library count ${key}`);
  }
  for (const key of Object.keys(countsValue)) {
    if (!(LIBRARY_SECTION_KEYS as readonly string[]).includes(key)) {
      throw new TvPayloadValidationError(`Library count key ${key} is unsupported`);
    }
  }
  const featured = list(summary.featured, 'Library featured').map(parseTvContentCard);
  return { counts, featured };
}

export type TvPlaylistDetail = {
  id: string;
  title: string;
  kind: 'playlist';
  artwork: string;
  creator: TvUserProfile | null;
  playCount: number;
  tags: string[];
  introduction: string;
  trackCount: number | null;
  tabs: string[];
  tracks: TvPagedResult<TvTrack>;
};

export type TvAlbumDetail = {
  id: string;
  title: string;
  kind: 'album';
  artwork: string;
  artist: string;
  publishDate: string;
  introduction: string;
  tabs: string[];
  tracks: TvPagedResult<TvTrack>;
};

export type TvArtistDetail = {
  id: string;
  title: string;
  kind: 'artist';
  artwork: string;
  summary: string;
  tabs: string[];
  hotTracks: TvTrack[];
  children: TvPagedResult<TvContentCard>;
};

export type TvSongDetail = {
  id: string;
  title: string;
  kind: 'song';
  artwork: string;
  tabs: string[];
  track: TvTrack | null;
  related: TvPagedResult<TvContentCard>;
};

export type TvMvDetailContent = {
  id: string;
  title: string;
  kind: 'mv';
  artwork: string;
  artist: string;
  durationMs: number;
  playCount: number;
  introduction: string;
  tabs: string[];
  related: TvPagedResult<TvContentCard>;
};

export type TvContentDetail = TvPlaylistDetail | TvAlbumDetail | TvArtistDetail | TvSongDetail | TvMvDetailContent;

function tabsOf(value: unknown): string[] {
  return list(value, 'Detail tabs').map((tab, index) => {
    try {
      return text(tab, `Detail tab ${index}`, true);
    } catch {
      throw new TvPayloadValidationError(`Detail tab ${index} is unsupported`);
    }
  });
}

function trackPageOf(value: unknown): TvPagedResult<TvTrack> {
  return parseTvPagedResult(value, parseTvTrack);
}

function cardPageOf(value: unknown): TvPagedResult<TvContentCard> {
  return parseTvPagedResult(value, parseTvContentCard);
}

function parseTvPlaylistDetail(detail: JsonRecord): TvPlaylistDetail {
  const creator = detail.creator;
  const trackCount = detail.trackCount;
  return {
    id: text(detail.id, 'Playlist detail id', true),
    title: text(detail.title, 'Playlist detail title', true),
    kind: 'playlist',
    artwork: text(detail.artwork, 'Playlist detail artwork'),
    creator: optional(creator) ? null : parseTvUserProfile(creator),
    playCount: count(detail.playCount, 'Playlist detail playCount'),
    tags: list(detail.tags, 'Playlist detail tags').map((tag, index) => text(tag, `Playlist tag ${index}`)),
    introduction: text(detail.introduction, 'Playlist detail introduction'),
    trackCount: optional(trackCount) ? null : count(trackCount, 'Playlist detail trackCount'),
    tabs: tabsOf(detail.tabs),
    tracks: trackPageOf(detail.tracks),
  };
}

function parseTvAlbumDetail(detail: JsonRecord): TvAlbumDetail {
  return {
    id: text(detail.id, 'Album detail id', true),
    title: text(detail.title, 'Album detail title', true),
    kind: 'album',
    artwork: text(detail.artwork, 'Album detail artwork'),
    artist: text(detail.artist, 'Album detail artist'),
    publishDate: text(detail.publishDate, 'Album detail publishDate'),
    introduction: text(detail.introduction, 'Album detail introduction'),
    tabs: tabsOf(detail.tabs),
    tracks: trackPageOf(detail.tracks),
  };
}

function parseTvArtistDetail(detail: JsonRecord): TvArtistDetail {
  return {
    id: text(detail.id, 'Artist detail id', true),
    title: text(detail.title, 'Artist detail title', true),
    kind: 'artist',
    artwork: text(detail.artwork, 'Artist detail artwork'),
    summary: text(detail.summary, 'Artist detail summary'),
    tabs: tabsOf(detail.tabs),
    hotTracks: list(detail.hotTracks, 'Artist hot tracks').map(parseTvTrack),
    children: cardPageOf(detail.children),
  };
}

function parseTvSongDetail(detail: JsonRecord): TvSongDetail {
  const track = detail.track;
  return {
    id: text(detail.id, 'Song detail id', true),
    title: text(detail.title, 'Song detail title', true),
    kind: 'song',
    artwork: text(detail.artwork, 'Song detail artwork'),
    tabs: tabsOf(detail.tabs),
    track: optional(track) ? null : parseTvTrack(track),
    related: cardPageOf(detail.related),
  };
}

function parseTvMvDetail(detail: JsonRecord): TvMvDetailContent {
  return {
    id: text(detail.id, 'MV detail id', true),
    title: text(detail.title, 'MV detail title', true),
    kind: 'mv',
    artwork: text(detail.artwork, 'MV detail artwork'),
    artist: text(detail.artist, 'MV detail artist'),
    durationMs: count(detail.durationMs, 'MV detail durationMs'),
    playCount: count(detail.playCount, 'MV detail playCount'),
    introduction: text(detail.introduction, 'MV detail introduction'),
    tabs: tabsOf(detail.tabs),
    related: cardPageOf(detail.related),
  };
}

export function parseTvContentDetail(value: unknown): TvContentDetail {
  const detail = record(value, 'Content detail');
  const kind = detail.kind;
  if (!isContentKind(kind)) throw new TvPayloadValidationError('Content detail kind is unsupported');
  switch (kind) {
    case 'playlist':
      return parseTvPlaylistDetail(detail);
    case 'album':
      return parseTvAlbumDetail(detail);
    case 'artist':
      return parseTvArtistDetail(detail);
    case 'song':
      return parseTvSongDetail(detail);
    case 'mv':
      return parseTvMvDetail(detail);
    case 'user':
      throw new TvPayloadValidationError('Content detail kind user is unsupported');
  }
}

export type TvFavoriteState = {
  kind: ContentKind;
  id: string;
  favorite: boolean;
};

export function parseTvFavoriteState(value: unknown): TvFavoriteState {
  const state = record(value, 'Favorite state');
  const kind = state.kind;
  if (!isContentKind(kind)) throw new TvPayloadValidationError('Favorite state kind is unsupported');
  if (typeof state.favorite !== 'boolean') throw new TvPayloadValidationError('Favorite state favorite must be a boolean');
  return { kind, id: text(state.id, 'Favorite state id', true), favorite: state.favorite };
}
