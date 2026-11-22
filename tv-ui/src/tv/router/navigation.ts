import type { RouteLocationNormalizedLoaded } from 'vue-router';

export const TOP_NAV_ORDER = ['home', 'explore', 'library', 'search', 'ranking', 'more'] as const;

export function navigationSection(route: Pick<RouteLocationNormalizedLoaded, 'name' | 'params' | 'query'>): string {
  const name = String(route.name ?? 'home');
  if (TOP_NAV_ORDER.some(id => id === name)) return name;
  if (['favorites', 'history', 'cloud', 'purchased'].includes(name) || String(route.params.id ?? '').includes('library_')) return 'library';
  if (name === 'ranking-detail') return 'ranking';
  if (['settings', 'about', 'recognize', 'plugin', 'share-resolve'].includes(name)) return 'more';
  const origin = String(route.query.sourceSection ?? '');
  if (TOP_NAV_ORDER.some(id => id === origin)) return origin;
  const source = String(route.query.sourceFocusId ?? '');
  if (/^(favorites|purchased|history|cloud|library)-/.test(source)) return 'library';
  if (source.startsWith('search-')) return 'search';
  if (source.startsWith('explore-')) return 'explore';
  return 'home';
}
