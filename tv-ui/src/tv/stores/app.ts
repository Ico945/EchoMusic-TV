import { ref } from 'vue';
import { defineStore } from 'pinia';
import { nativeClient } from '../native/client';
import type { TvHome } from './content';
import { parseTvSnapshot } from './parsers';

export type TvTrack = {
  id: string;
  title: string;
  artist: string;
  album: string;
  artwork: string;
  durationMs: number;
  mv?: TvMvDetail;
};

export type TvMvDetail = {
  id: string;
  title: string;
  artist: string;
  quality: string;
  qualities: readonly string[];
};

export type TvSessionStatus = 'loggedOut' | 'expired' | 'qrPending' | 'loggedIn';

export type TvRouteId = 'home' | 'explore' | 'fm' | 'ranking' | 'library' | 'search' | 'more';

export type TvSession = { status: TvSessionStatus; displayName?: string; membership?: string };

export type TvSnapshot = { route: TvRouteId; session: TvSession; bootstrapHome?: TvHome };

export const useTvAppStore = defineStore('tv-app', () => {
  const route = ref<TvRouteId>('home');
  const session = ref<TvSession>({ status: 'loggedOut' });
  let snapshotGeneration = 0;

  async function loadSnapshot(): Promise<TvSnapshot> {
    const generation = ++snapshotGeneration;
    try {
      const snapshot = parseTvSnapshot(await nativeClient.request<unknown>('app.getSnapshot', {}));
      if (generation !== snapshotGeneration) return { route: route.value, session: session.value };
      route.value = snapshot.route;
      session.value = snapshot.session;
      return snapshot;
    } catch {
      return { route: route.value, session: session.value };
    }
  }

  return { route, session, loadSnapshot };
});
