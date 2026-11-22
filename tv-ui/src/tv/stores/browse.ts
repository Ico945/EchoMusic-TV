import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { nativeClient } from '../native/client';
import { parseTvTrack, TvPayloadValidationError } from './parsers';
import { useTvAppStore } from './app';
import type { TvTrack } from './app';
import {
  parseTvContentCard,
  parseTvPagedResult,
  parseTvRanking,
  type TvContentCard,
  type TvRankingSummary,
} from '../native/contentTypes';
import { isNativeBridgeAvailable } from '../native/transport';

export type ShelfStatus = 'loading' | 'ready' | 'error';

export type ExploreShelf = {
  id: 'newSongs' | 'newAlbums' | 'newPlaylists' | 'artists' | 'rankings';
  title: string;
  status: ShelfStatus;
  tracks: TvTrack[];
  cards: TvContentCard[];
  rankings: TvRankingSummary[];
};

const SHELF_TITLES: Record<ExploreShelf['id'], string> = {
  newSongs: '新歌速递',
  newAlbums: '新碟上架',
  newPlaylists: '推荐歌单',
  artists: '热门歌手',
  rankings: '排行榜',
};

function emptyShelf(id: ExploreShelf['id']): ExploreShelf {
  return { id, title: SHELF_TITLES[id], status: 'loading', tracks: [], cards: [], rankings: [] };
}

function parseExplore(value: unknown): { shelves: ExploreShelf[] } {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error('explore payload must be an object');
  }
  const record = value as Record<string, unknown>;
  const failedSections = record.failedSections === undefined ? [] : record.failedSections;
  if (!Array.isArray(failedSections) || failedSections.some(id => typeof id !== 'string' || !Object.prototype.hasOwnProperty.call(SHELF_TITLES, id))) {
    throw new Error('explore failedSections must contain known section ids');
  }
  const list = (key: string): unknown[] => (Array.isArray(record[key]) ? (record[key] as unknown[]) : []);
  const shelves: ExploreShelf[] = [
    { ...emptyShelf('newSongs'), status: 'ready', cards: list('newSongs').map(parseTvContentCard) },
    { ...emptyShelf('newAlbums'), status: 'ready', cards: list('newAlbums').map(parseTvContentCard) },
    { ...emptyShelf('newPlaylists'), status: 'ready', cards: list('newPlaylists').map(parseTvContentCard) },
    { ...emptyShelf('artists'), status: 'ready', cards: list('artists').map(parseTvContentCard) },
    { ...emptyShelf('rankings'), status: 'ready', rankings: list('rankings').map(parseTvRanking) },
  ];
  return { shelves: shelves.map(shelf => failedSections.includes(shelf.id) ? { ...shelf, status: 'error' } : shelf) };
}

export const useTvBrowseStore = defineStore('tv-browse', () => {
  const shelves = ref<ExploreShelf[]>([]);
  const loaded = ref(false);
  let exploreRequest: Promise<void> | undefined;
  const retryingShelves = new Set<ExploreShelf['id']>();

  function loadExplore(): Promise<void> {
    if (exploreRequest) return exploreRequest;
    exploreRequest = performLoadExplore().finally(() => { exploreRequest = undefined; });
    return exploreRequest;
  }

  async function performLoadExplore(): Promise<void> {
    loaded.value = false;
    if (!isNativeBridgeAvailable()) {
      shelves.value = [];
      loaded.value = true;
      return;
    }
    shelves.value = (['newSongs', 'newAlbums', 'newPlaylists', 'artists', 'rankings'] as const).map(emptyShelf);
    try {
      const payload = await nativeClient.request<unknown>('content.explore', {});
      shelves.value = parseExplore(payload).shelves;
    } catch {
      shelves.value = shelves.value.map((shelf) => ({ ...shelf, status: 'error' as ShelfStatus }));
    } finally {
      loaded.value = true;
    }
  }

  async function retryShelf(id: ExploreShelf['id']): Promise<void> {
    if (retryingShelves.has(id)) return;
    const index = shelves.value.findIndex((shelf) => shelf.id === id);
    if (index < 0) return;
    retryingShelves.add(id);
    shelves.value[index] = { ...shelves.value[index], status: 'loading' };
    try {
      const payload = await nativeClient.request<unknown>('content.explore', {});
      const next = parseExplore(payload).shelves.find((shelf) => shelf.id === id);
      shelves.value[index] = next ?? { ...emptyShelf(id), status: 'error' };
    } catch {
      shelves.value[index] = { ...shelves.value[index], status: 'error' };
    } finally {
      retryingShelves.delete(id);
    }
  }

  const anyReady = computed(() => shelves.value.some((shelf) => shelf.status === 'ready'));

  const dailyTracks = ref<TvTrack[]>([]);
  const dailyStatus = ref<ShelfStatus>('loading');
  const dailyErrorCode = ref<string | null>(null);
  let dailyGeneration = 0;
  const app = useTvAppStore();
  watch([() => app.session.status, () => app.session.displayName], () => {
    dailyGeneration++;
    dailyTracks.value = [];
    dailyErrorCode.value = null;
    dailyStatus.value = 'loading';
  }, { flush: 'sync' });

  async function loadDaily(): Promise<void> {
    const generation = ++dailyGeneration;
    dailyStatus.value = 'loading';
    dailyErrorCode.value = null;
    if (!isNativeBridgeAvailable()) {
      dailyTracks.value = [];
      dailyStatus.value = 'error';
      dailyErrorCode.value = 'BRIDGE_UNAVAILABLE';
      return;
    }
    try {
      const payload = await nativeClient.request<unknown>('content.dailyRecommendations', {});
      if (generation !== dailyGeneration) return;
      const items = typeof payload === 'object' && payload !== null && !Array.isArray(payload)
        ? (payload as Record<string, unknown>).items
        : undefined;
      if (!Array.isArray(items)) throw new TvPayloadValidationError('Daily items must be an array');
      dailyTracks.value = items.map(parseTvTrack);
      dailyStatus.value = 'ready';
    } catch (error) {
      if (generation !== dailyGeneration) return;
      dailyStatus.value = 'error';
      const code = typeof error === 'object' && error !== null && 'code' in error ? error.code : null;
      dailyErrorCode.value = error instanceof TvPayloadValidationError ? 'CONTENT_INVALID'
        : typeof code === 'string' && /^[A-Z_]{3,48}$/.test(code) ? code : 'DAILY_REQUEST_FAILED';
    }
  }

  const fmTracks = ref<TvTrack[]>([]);
  const fmStatus = ref<ShelfStatus>('loading');

  async function loadFm(): Promise<void> {
    fmStatus.value = 'loading';
    if (!isNativeBridgeAvailable()) {
      fmTracks.value = [];
      fmStatus.value = 'error';
      return;
    }
    try {
      const payload = await nativeClient.request<unknown>('content.privateFm', {});
      const items = typeof payload === 'object' && payload !== null && !Array.isArray(payload)
        ? (payload as Record<string, unknown>).items
        : undefined;
      fmTracks.value = Array.isArray(items) ? items.map(parseTvTrack) : [];
      fmStatus.value = 'ready';
    } catch {
      fmStatus.value = 'error';
    }
  }

  async function discardFm(trackId: string): Promise<boolean> {
    try {
      await nativeClient.request<unknown>('content.privateFmDiscard', { trackId });
      return true;
    } catch {
      return false;
    }
  }

  function advanceFm(): void {
    if (fmTracks.value.length <= 1) {
      void loadFm();
      return;
    }
    fmTracks.value = fmTracks.value.slice(1);
  }

  const rankingTracks = ref<TvTrack[]>([]);
  const rankingId = ref('');
  let rankingGeneration = 0;
  let activeRankingRequest = '';
  const rankingNextCursor = ref<string | null>(null);
  const rankingTotal = ref<number | null>(null);
  const rankingStatus = ref<ShelfStatus>('loading');

  async function loadRanking(id: string, cursor?: string): Promise<void> {
    const key = `${id}:${cursor ?? ''}`;
    if (activeRankingRequest === key) return;
    if (cursor !== undefined && id !== rankingId.value) return;
    activeRankingRequest = key;
    const generation = ++rankingGeneration;
    rankingId.value = id;
    if (cursor === undefined) {
      rankingTracks.value = [];
      rankingNextCursor.value = null;
      rankingTotal.value = null;
    }
    rankingStatus.value = 'loading';
    try {
      const payload = await nativeClient.request<unknown>('content.ranking', {
        id,
        ...(cursor === undefined ? {} : { cursor }),
      });
      if (generation !== rankingGeneration) return;
      const { items, nextCursor, total } = parseTvPagedResult(payload, parseTvTrack);
      rankingTracks.value = cursor === undefined ? items
        : Array.from(new Map([...rankingTracks.value, ...items].map(item => [item.id, item])).values());
      rankingNextCursor.value = nextCursor;
      rankingTotal.value = total;
      rankingStatus.value = 'ready';
    } catch {
      if (generation !== rankingGeneration) return;
      if (cursor === undefined) rankingTracks.value = [];
      rankingStatus.value = 'error';
    } finally {
      if (generation === rankingGeneration) activeRankingRequest = '';
    }
  }

  return {
    shelves,
    loaded,
    anyReady,
    dailyTracks,
    dailyStatus,
    dailyErrorCode,
    fmTracks,
    fmStatus,
    rankingTracks,
    rankingId,
    rankingNextCursor,
    rankingTotal,
    rankingStatus,
    loadExplore,
    retryShelf,
    loadDaily,
    loadFm,
    discardFm,
    advanceFm,
    loadRanking,
  };
});
