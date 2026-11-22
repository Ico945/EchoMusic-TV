import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { nativeClient } from '../native/client';
import {
  parseTvContentCard,
  parseLibrarySyncInfo,
  parseTvFavoriteState,
  parseTvLibrarySummary,
  parseTvPagedResult,
  type ContentKind,
  type LibrarySectionKey,
  type LibrarySyncInfo,
  type TvContentCard,
  type TvLibrarySummary,
} from '../native/contentTypes';
import { isNativeBridgeAvailable } from '../native/transport';
import { useTvAppStore } from './app';
import { TvPayloadValidationError } from './parsers';

export type SectionStatus = 'loading' | 'ready' | 'error';
export type LibraryBrowseKey = LibrarySectionKey | 'allPlaylists';

export type LibrarySectionState = {
  items: TvContentCard[];
  nextCursor: string | null;
  total: number | null;
  status: SectionStatus;
  errorCode?: string;
  syncPages?: Record<number, LibrarySyncInfo>;
};

function emptySection(): LibrarySectionState {
  return { items: [], nextCursor: null, total: null, status: 'loading' };
}

function createRequestId(): string {
  const suffix = Math.random().toString(16).slice(2, 10);
  return `lib-${Date.now().toString(36)}-${suffix}`;
}

const FAVORITE_SECTION_BY_KIND: Partial<Record<ContentKind, LibrarySectionKey>> = {
  song: 'favoriteSongs',
  artist: 'favoriteArtists',
  user: 'favoriteUsers',
  album: 'favoriteAlbums',
  mv: 'favoriteMvs',
  playlist: 'favoritePlaylists',
};

export const useTvLibraryStore = defineStore('tv-library', () => {
  const summary = ref<TvLibrarySummary | null>(null);
  const summaryStatus = ref<SectionStatus>('loading');
  const sections = ref<Record<string, LibrarySectionState>>({});
  const requests = new Map<string, symbol>();
  let summaryGeneration = 0;
  const app = useTvAppStore();

  function reset(): void {
    summaryGeneration++;
    requests.clear();
    sections.value = {};
    summary.value = null;
    summaryStatus.value = 'loading';
  }

  // Invalidate pending responses as well as visible account-specific data.
  watch([() => app.session.status, () => app.session.displayName], reset, { flush: 'sync' });

  const featured = computed(() => summary.value?.featured ?? []);

  const myPlaylists = computed<TvContentCard[]>(() => {
    const created = sections.value.createdPlaylists?.items ?? [];
    const favorited = sections.value.favoritePlaylists?.items ?? [];
    return [...created, ...favorited];
  });

  function sectionState(section: LibraryBrowseKey): LibrarySectionState {
    if (!sections.value[section]) sections.value[section] = emptySection();
    return sections.value[section];
  }

  async function loadSummary(): Promise<void> {
    const generation = ++summaryGeneration;
    summaryStatus.value = 'loading';
    if (!isNativeBridgeAvailable()) {
      summary.value = { counts: {}, featured: [] };
      summaryStatus.value = 'error';
      return;
    }
    try {
      const payload = await nativeClient.request<unknown>('library.get', {});
      if (generation !== summaryGeneration) return;
      summary.value = parseTvLibrarySummary(payload);
      summaryStatus.value = 'ready';
    } catch {
      if (generation !== summaryGeneration) return;
      summaryStatus.value = 'error';
    }
  }

  async function loadSection(section: LibraryBrowseKey, cursor?: string): Promise<void> {
    if (requests.has(section)) return;
    const current = sectionState(section);
    if (cursor !== undefined && cursor !== current.nextCursor) return;
    current.status = 'loading';
    current.errorCode = undefined;
    if (!isNativeBridgeAvailable()) {
      current.status = 'error';
      return;
    }
    const owner = Symbol(section);
    requests.set(section, owner);
    try {
      let next = cursor;
      const seen = new Set<string>();
      // Echo's upstream pages contain mixed playlist types. Walk empty filtered
      // pages without asking the viewer to repeatedly confirm an empty screen.
      // Bound work per action; a remaining cursor stays available in the UI.
      for (let attempt = 0; attempt < 5; attempt++) {
        const cursorKey = next ?? 'first';
        if (seen.has(cursorKey)) throw new Error('Repeated library cursor');
        seen.add(cursorKey);
        const payload = await nativeClient.request<unknown>(section === 'allPlaylists' ? 'library.playlists' : 'library.section', {
          ...(section === 'allPlaylists' ? {} : { section }),
          ...(next === undefined ? {} : { cursor: next }),
        });
        if (requests.get(section) !== owner) return;
        const page = parseTvPagedResult(payload, parseTvContentCard);
        const syncInfo = parseLibrarySyncInfo((payload as Record<string, unknown>).librarySync);
        if (attempt === 0 && cursor === undefined) current.syncPages = {};
        if (syncInfo) (current.syncPages ??= {})[syncInfo.page] = syncInfo;
        const items = attempt === 0 && cursor === undefined ? page.items : [...current.items, ...page.items];
        current.items = [...new Map(items.map((item) => [`${item.kind}:${item.id}`, item])).values()];
        current.nextCursor = page.nextCursor;
        current.total = page.total;
        if (page.items.length || !page.nextCursor) break;
        next = page.nextCursor;
      }
      current.status = 'ready';
    } catch (error) {
      if (requests.get(section) !== owner) return;
      current.errorCode = error instanceof TvPayloadValidationError ? 'LIBRARY_UI_PAYLOAD' :
        typeof error === 'object' && error !== null && 'code' in error && typeof error.code === 'string' ? error.code : 'LIBRARY_REQUEST_FAILED';
      current.status = 'error';
    } finally {
      if (requests.get(section) === owner) requests.delete(section);
    }
  }

  async function deleteCloudItem(trackId: string): Promise<boolean> {
    const cloud = sectionState('cloud');
    const index = cloud.items.findIndex((card) => card.id === trackId);
    const removed = index >= 0 ? cloud.items.splice(index, 1)[0] : undefined;
    try {
      await nativeClient.request<unknown>('library.deleteCloudItem', { trackId, requestId: createRequestId() });
      return true;
    } catch {
      if (removed && index >= 0) cloud.items.splice(index, 0, removed);
      return false;
    }
  }

  async function favorite(kind: ContentKind, id: string, next: boolean): Promise<boolean> {
    const generation = summaryGeneration;
    const canonical = (value: string) => value.startsWith(`${kind}:`) ? value : `${kind}:${value}`;
    const sectionKey = FAVORITE_SECTION_BY_KIND[kind];
    const section = sectionKey ? sections.value[sectionKey] : undefined;
    const index = section ? section.items.findIndex((card) => canonical(card.id) === canonical(id)) : -1;
    const removed = section && index >= 0 && !next ? section.items.splice(index, 1)[0] : undefined;
    try {
      const payload = await nativeClient.request<unknown>('library.favorite', {
        kind,
        id,
        favorite: next,
        requestId: createRequestId(),
      });
      const state = parseTvFavoriteState(payload);
      if (generation !== summaryGeneration) return false;
      if (state.kind !== kind || canonical(state.id) !== canonical(id) || state.favorite !== next) throw new Error('Favorite state was not confirmed');
      if (removed && section?.total !== null && section?.total !== undefined) section.total = Math.max(0, section.total - 1);
      // Reload a cached collection after confirmed additions, retaining its
      // visible items during the read. Do not fabricate a card or success.
      if (next && index < 0 && sectionKey && section) void loadSection(sectionKey);
      return true;
    } catch {
      if (generation === summaryGeneration && removed && section && index >= 0) section.items.splice(index, 0, removed);
      return false;
    }
  }

  async function addToPlaylist(playlistId: string, trackId: string): Promise<boolean> {
    try {
      await nativeClient.request<unknown>('library.addToPlaylist', { playlistId, trackId, requestId: createRequestId() });
      return true;
    } catch {
      return false;
    }
  }

  async function logout(): Promise<boolean> {
    try {
      await nativeClient.request<unknown>('auth.logout', {});
      reset();
      return true;
    } catch {
      return false;
    }
  }

  return {
    summary,
    summaryStatus,
    featured,
    sections,
    myPlaylists,
    loadSummary,
    loadSection,
    deleteCloudItem,
    favorite,
    addToPlaylist,
    logout,
    reset,
  };
});
