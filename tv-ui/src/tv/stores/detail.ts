import { ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { nativeClient } from '../native/client';
import {
  parseTvComment,
  parseTvContentCard,
  parseTvContentDetail,
  parseTvPagedResult,
  type ContentKind,
  type TvComment,
  type TvContentCard,
  type TvContentDetail,
} from '../native/contentTypes';
import { parseTvTrack } from './parsers';
import { useTvAppStore, type TvTrack } from './app';
import { useTvLibraryStore, type LibraryBrowseKey } from './library';

export type DetailStatus = 'idle' | 'loading' | 'ready' | 'error';

export type ChildPage = {
  items: TvContentCard[];
  tracks: TvTrack[];
  nextCursor: string | null;
  total: number | null;
};

const emptyChildPage: ChildPage = { items: [], tracks: [], nextCursor: null, total: null };

export function detailContentKey(kind: string, id: string): string {
  const prefix = `${kind}:`;
  return id.startsWith(prefix) ? id : `${prefix}${id}`;
}

export function detailChildKey(kind: string, id: string, tab: string): string {
  return `${detailContentKey(kind, id)}:${tab}`;
}

function parseChildPage(value: unknown, tab: string): ChildPage {
  const page = parseTvPagedResult(value, (item) => item);
  if (tab === 'songs') {
    // Artist children retain their card contract; native adds playable tracks.
    // Playlist/album children continue to supply tracks directly in items.
    const tracks = value !== null && typeof value === 'object' && 'tracks' in value ? value.tracks : page.items;
    const parsed = parseTvPagedResult({ ...page, items: tracks }, parseTvTrack);
    return { items: [], tracks: parsed.items, nextCursor: parsed.nextCursor, total: parsed.total };
  }
  return { items: page.items.map(parseTvContentCard), tracks: [], nextCursor: page.nextCursor, total: page.total };
}

export const useTvDetailStore = defineStore('tv-detail', () => {
  const detail = ref<TvContentDetail | null>(null);
  const status = ref<DetailStatus>('idle');
  const activeTab = ref('songs');
  const children = ref<Record<string, ChildPage>>({});
  const childrenStatus = ref<Record<string, 'loading' | 'ready' | 'error'>>({});
  const comments = ref<{ items: TvComment[]; nextCursor: string | null; total: number | null }>({ items: [], nextCursor: null, total: null });
  const commentsStatus = ref<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const favorite = ref<Record<string, boolean>>({});
  const favoritePending = ref<Record<string, boolean>>({});
  const library = useTvLibraryStore();
  const app = useTvAppStore();
  let favoriteEpoch = 0;
  watch([() => app.session.status, () => app.session.displayName], () => {
    favoriteEpoch++; favorite.value = {}; favoritePending.value = {};
  }, { flush: 'sync' });
  let detailGeneration = 0;
  const childRequests = new Map<string, symbol>();

  function key(kind: string, id: string): string {
    return detailContentKey(kind, id);
  }

  function favoriteState(kind: ContentKind, id: string): boolean | undefined {
    const target = key(kind, id);
    if (target in favorite.value) return favorite.value[target];
    const sectionKey = { song: 'favoriteSongs', album: 'favoriteAlbums', artist: 'favoriteArtists', playlist: 'favoritePlaylists', mv: 'favoriteMvs', user: 'favoriteUsers' }[kind];
    const section = sectionKey ? library.sections[sectionKey] : undefined;
    if (section?.items.some(item => key(kind, item.id) === target)) return true;
    return section?.status === 'ready' && !section.nextCursor ? false : undefined;
  }

  async function primeFavoriteState(kind: ContentKind): Promise<void> {
    if (app.session.status !== 'loggedIn') return;
    const section = { song: 'favoriteSongs', artist: 'favoriteArtists', album: 'favoriteAlbums', playlist: 'favoritePlaylists', mv: 'favoriteMvs', user: 'favoriteUsers' }[kind] as LibraryBrowseKey;
    if (section && !library.sections[section]) await library.loadSection(section);
  }

  async function loadDetail(kind: string, id: string): Promise<void> {
    const generation = ++detailGeneration;
    // A superseded request must not leave a permanent loading lock when its
    // detail is revisited. Its eventual completion no longer owns this key.
    for (const childKey of childRequests.keys()) delete childrenStatus.value[childKey];
    childRequests.clear();
    detail.value = null;
    comments.value = { items: [], nextCursor: null, total: null };
    commentsStatus.value = 'idle';
    status.value = 'loading';
    try {
      const payload = await nativeClient.request<unknown>('content.detail', { kind, id });
      if (generation !== detailGeneration) return;
      const parsed = parseTvContentDetail(payload);
      void primeFavoriteState(parsed.kind);
      detail.value = parsed;
      activeTab.value = parsed.tabs[0] ?? 'songs';
      const detailKey = key(parsed.kind, parsed.id);
      if (parsed.kind === 'playlist' || parsed.kind === 'album') {
        const songsKey = `${detailKey}:songs`;
        const cached = children.value[songsKey];
        const cacheReady = childrenStatus.value[songsKey] === 'ready' && Boolean(cached?.tracks.length);
        // A metadata refresh is not a refresh of a paginated child list.
        // Preserve its full snapshot unless upstream explicitly confirms empty.
        if (!cacheReady || parsed.tracks.total === 0) {
          delete childrenStatus.value[songsKey];
          children.value[songsKey] = {
            items: [],
            tracks: parsed.tracks.items.map(parseTvTrack),
            nextCursor: parsed.tracks.nextCursor,
            total: parsed.tracks.total,
          };
          if (parsed.tracks.items.length > 0 || parsed.tracks.total === 0) childrenStatus.value[songsKey] = 'ready';
        }
      }
      if (parsed.kind === 'artist') {
        const seeds: Record<string, ChildPage> = {
          songs: { items: [], tracks: parsed.hotTracks.map(parseTvTrack), nextCursor: null, total: null },
          albums: { items: parsed.children.items, tracks: [], nextCursor: parsed.children.nextCursor, total: parsed.children.total },
          mv: emptyChildPage,
        };
        for (const [tab, seed] of Object.entries(seeds)) {
          const childKey = `${detailKey}:${tab}`;
          const cached = children.value[childKey];
          if (childrenStatus.value[childKey] === 'ready' && cached && (cached.items.length || cached.tracks.length || cached.total === 0)) continue;
          children.value[childKey] = seed;
          delete childrenStatus.value[childKey];
          if (seed.items.length || seed.tracks.length || seed.total === 0) childrenStatus.value[childKey] = 'ready';
        }
      }
      status.value = 'ready';
    } catch {
      if (generation === detailGeneration) status.value = 'error';
    }
  }

  async function loadChildren(kind: string, id: string, tab: string, cursor?: string): Promise<void> {
    const generation = detailGeneration;
    const childKey = detailChildKey(kind, id, tab);
    if (childRequests.has(childKey)) return;
    const owner = Symbol(childKey);
    childRequests.set(childKey, owner);
    const ownsRequest = () => generation === detailGeneration && childRequests.get(childKey) === owner;
    childrenStatus.value[childKey] = 'loading';
    try {
      const payload = await nativeClient.request<unknown>('content.detailChildren', {
        kind,
        id,
        tab,
        ...(cursor === undefined ? {} : { cursor }),
      });
      if (!ownsRequest()) return;
      const page = parseChildPage(payload, tab);
      const previous = children.value[childKey] ?? emptyChildPage;
      children.value[childKey] = cursor === undefined
        ? page
        : {
            items: [...new Map([...previous.items, ...page.items].map(item => [item.id, item])).values()],
            tracks: [...new Map([...previous.tracks, ...page.tracks].map(track => [track.id, track])).values()],
            nextCursor: page.nextCursor,
            total: page.total,
          };
      childrenStatus.value[childKey] = 'ready';
    } catch {
      if (ownsRequest()) childrenStatus.value[childKey] = 'error';
    } finally {
      if (childRequests.get(childKey) === owner) childRequests.delete(childKey);
    }
  }

  async function loadComments(kind: string, id: string, cursor?: string): Promise<void> {
    const generation = detailGeneration;
    commentsStatus.value = 'loading';
    try {
      const payload = await nativeClient.request<unknown>('content.comments', {
        kind,
        id,
        ...(cursor === undefined ? {} : { cursor }),
      });
      const page = parseTvPagedResult(payload, parseTvComment);
      if (generation !== detailGeneration) return;
      comments.value = cursor === undefined
        ? page
        : { ...page, items: [...comments.value.items, ...page.items] };
      commentsStatus.value = 'ready';
    } catch {
      if (generation === detailGeneration) commentsStatus.value = 'error';
    }
  }

  async function toggleFavorite(kind: ContentKind, id: string, next: boolean): Promise<boolean> {
    const favoriteKey = key(kind, id);
    if (favoritePending.value[favoriteKey]) return false;
    const epoch = favoriteEpoch;
    favoritePending.value[favoriteKey] = true;
    try {
      const ok = await library.favorite(kind, id, next);
      if (epoch !== favoriteEpoch) return false;
      if (ok) favorite.value[favoriteKey] = next;
      return ok;
    } finally {
      if (epoch === favoriteEpoch) delete favoritePending.value[favoriteKey];
    }
  }

  return {
    detail,
    status,
    activeTab,
    children,
    childrenStatus,
    comments,
    commentsStatus,
    favorite,
    favoritePending,
    favoriteState,
    primeFavoriteState,
    loadDetail,
    loadChildren,
    loadComments,
    toggleFavorite,
  };
});
