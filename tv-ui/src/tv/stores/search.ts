import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { nativeClient } from '../native/client';
import {
  parseTvContentCard,
  parseTvPagedResult,
  SEARCH_CATEGORIES,
  type SearchCategory,
  type TvContentCard,
} from '../native/contentTypes';
import { isNativeBridgeAvailable } from '../native/transport';

const HISTORY_KEY = 'tv-search-history';
const HISTORY_LIMIT = 8;

export type SearchPage = {
  items: TvContentCard[];
  nextCursor: string | null;
  total: number | null;
};

const emptyPage: SearchPage = { items: [], nextCursor: null, total: null };

function parseSearchResponse(value: unknown): { category: SearchCategory; page: SearchPage } {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error('search payload must be an object');
  }
  const record = value as Record<string, unknown>;
  const category = record.category;
  if (typeof category !== 'string' || !(SEARCH_CATEGORIES as readonly string[]).includes(category)) {
    throw new Error('search category is unsupported');
  }
  return { category: category as SearchCategory, page: parseTvPagedResult(value, parseTvContentCard) };
}

function loadHistory(): string[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((item): item is string => typeof item === 'string' && item.length > 0).slice(0, HISTORY_LIMIT);
  } catch {
    return [];
  }
}

function saveHistory(terms: string[]): void {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(terms.slice(0, HISTORY_LIMIT)));
  } catch {
    // Local history is best-effort on TV devices with storage restrictions.
  }
}

export const useTvSearchStore = defineStore('tv-search', () => {
  const query = ref('');
  const submittedQuery = ref('');
  const hotLoading = ref(false);
  const hotError = ref(false);
  let generation = 0;
  let failedCursor: string | undefined;
  const category = ref<SearchCategory>('song');
  const results = ref<SearchPage>(emptyPage);
  const loading = ref(false);
  const error = ref(false);
  const requiresLogin = ref(false);
  const hotTerms = ref<string[]>([]);
  const history = ref<string[]>(loadHistory());
  const submitted = ref(false);
  function clearHistory(): void { history.value = []; saveHistory([]); }

  async function loadHotAndHistory(): Promise<void> {
    if (hotLoading.value) return;
    history.value = loadHistory();
    if (!isNativeBridgeAvailable()) return;
    hotLoading.value = true;
    hotError.value = false;
    try {
      const payload = await nativeClient.request<unknown>('content.hotSearch', {});
      if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) throw new Error('Invalid hot search');
      const terms = (payload as Record<string, unknown>).terms;
      if (Array.isArray(terms)) {
        hotTerms.value = terms.filter((term): term is string => typeof term === 'string' && term.length > 0).slice(0, 12);
      } else throw new Error('Invalid hot terms');
    } catch {
      hotError.value = true;
    } finally {
      hotLoading.value = false;
    }
  }

  async function runSearch(term: string, nextCategory: SearchCategory, cursor?: string): Promise<void> {
    const effectiveQuery = term.trim();
    if (!effectiveQuery) return;
    const request = ++generation;
    loading.value = true;
    error.value = false;
    requiresLogin.value = false;
    failedCursor = cursor;
    if (cursor === undefined) results.value = { ...emptyPage };
    try {
      const payload = await nativeClient.request<unknown>('content.search', {
        query: effectiveQuery,
        category: nextCategory,
        ...(cursor === undefined ? {} : { cursor }),
      });
      if (request !== generation) return;
      const parsed = parseSearchResponse(payload);
      if (parsed.category !== nextCategory) throw new Error('search category mismatch');
      results.value = cursor === undefined
        ? parsed.page
        : { ...parsed.page, items: Array.from(new Map([...results.value.items, ...parsed.page.items].map(item => [item.id, item])).values()) };
      if (cursor === undefined) {
        history.value = [effectiveQuery, ...history.value.filter((item) => item !== effectiveQuery)].slice(0, HISTORY_LIMIT);
        saveHistory(history.value);
      }
    } catch (failure) {
      if (request === generation) {
        error.value = true;
        requiresLogin.value = typeof failure === 'object' && failure !== null && 'code' in failure && failure.code === 'AUTH_EXPIRED';
      }
    } finally {
      if (request === generation) loading.value = false;
    }
  }

  async function submit(): Promise<void> {
    if (!query.value.trim()) return;
    submittedQuery.value = query.value.trim();
    submitted.value = true;
    await runSearch(submittedQuery.value, category.value);
  }

  async function setCategory(next: SearchCategory): Promise<void> {
    if (next === category.value) return;
    category.value = next;
    if (!submitted.value) return;
    await runSearch(submittedQuery.value, next);
  }

  async function searchNext(): Promise<void> {
    const cursor = results.value.nextCursor;
    if (!cursor || loading.value) return;
    await runSearch(submittedQuery.value, category.value, cursor);
  }

  async function retry(): Promise<void> {
    if (loading.value || !submitted.value) return;
    await runSearch(submittedQuery.value, category.value, failedCursor);
  }

  function showSuggestions(): void {
    generation++;
    submitted.value = false;
    submittedQuery.value = '';
    results.value = { ...emptyPage };
    loading.value = false;
    error.value = false;
    requiresLogin.value = false;
    failedCursor = undefined;
  }

  // LYRIC returns song cards too and uses the same remote song-row interaction.
  const isSongCategory = computed(() => category.value === 'song' || category.value === 'lyric');
  const firstResultFocusId = computed(() => results.value.items.length
    ? `search-${category.value}-${isSongCategory.value ? results.value.items[0].id : 0}`
    : undefined);
  return {
    query,
    submittedQuery,
    hotLoading,
    hotError,
    isSongCategory,
    retry,
    showSuggestions,
    category,
    results,
    loading,
    error,
    requiresLogin,
    hotTerms,
    history,
    submitted,
    firstResultFocusId,
    loadHotAndHistory,
    clearHistory,
    submit,
    setCategory,
    searchNext,
  };
});
