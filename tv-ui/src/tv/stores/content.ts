import { readonly, ref } from 'vue';
import { defineStore } from 'pinia';
import { nativeClient } from '../native/client';
import { isNativeBridgeAvailable } from '../native/transport';
import type { TvTrack } from './app';
import { parseTvHome } from './parsers';

export type TvShelf = { id: string; title: string; capability: string; tracks: TvTrack[] };

export type TvHome = {
  greeting: string;
  hero: TvTrack;
  secondary: TvTrack[];
  shelves: TvShelf[];
};

export type TvHomeStatus = 'idle' | 'loading' | 'ready' | 'error';

export const useTvContentStore = defineStore('tv-content', () => {
  const home = ref<TvHome | null>(null);
  const status = ref<TvHomeStatus>('idle');
  const errorMessage = ref('');
  let homeGeneration = 0;

  async function loadHome(bootstrapHome?: unknown): Promise<void> {
    const generation = ++homeGeneration;
    try {
      if (bootstrapHome !== undefined) {
        home.value = parseTvHome(bootstrapHome);
        status.value = 'ready';
        errorMessage.value = '';
      }
    } catch {
      if (home.value === null) {
        status.value = 'error';
        errorMessage.value = '首页数据无效，请重试';
      }
      return;
    }
    if (!isNativeBridgeAvailable()) {
      if (home.value === null) {
        status.value = 'error';
        errorMessage.value = '请在 Android TV 应用中打开';
      }
      return;
    }

    if (home.value === null) status.value = 'loading';
    try {
      const payload = await nativeClient.request<unknown>('content.home', {});
      if (generation !== homeGeneration) return;
      home.value = parseTvHome(payload);
      status.value = 'ready';
      errorMessage.value = '';
    } catch {
      if (generation !== homeGeneration) return;
      if (home.value === null) {
        status.value = 'error';
        errorMessage.value = '内容加载失败，请重试';
      }
    }
  }

  return {
    home: readonly(home),
    status: readonly(status),
    errorMessage: readonly(errorMessage),
    loadHome,
  };
});
