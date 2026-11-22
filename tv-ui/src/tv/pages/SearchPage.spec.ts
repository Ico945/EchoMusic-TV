import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import { createTvRouter } from '../router';
import SearchPage from './SearchPage.vue';
import SongList from '../content/SongList.vue';
import SongContextMenu from '../content/SongContextMenu.vue';

function installSearchClient(overrides: Record<string, unknown> = {}) {
  const client: NativeClientLike = {
    request: vi.fn((method: string, payload: unknown) => {
      const record = payload as Record<string, unknown> | undefined;
      if (method === 'content.hotSearch') {
        return Promise.resolve({ terms: ['若把你', '孤勇者'] });
      }
      if (method === 'content.search') {
        const category = record?.category;
        if (category === 'album') {
          return Promise.resolve({
            category: 'album',
            items: [
              { id: 'album:1', title: '专辑一', kind: 'album', subtitle: '歌手一', artwork: '' },
              { id: 'album:2', title: '专辑二', kind: 'album', subtitle: '歌手二', artwork: '' },
            ],
            nextCursor: null,
            total: 2,
          });
        }
        return Promise.resolve({
          category: 'song',
          items: [{ id: 'tv_song_1', title: '歌曲一', kind: 'song', subtitle: '歌手', artwork: '' }],
          nextCursor: null,
          total: 1,
        });
      }
      if (method === 'library.favorite') {
        return Promise.resolve({ kind: 'song', id: 'tv_song_1', favorite: true });
      }
      return Promise.resolve(overrides[method] ?? {});
    }) as NativeClientLike['request'],
    on: () => () => undefined,
  };
  installNativeClientForTest(client);
  return client;
}

function mountSearch() {
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  setActivePinia(createPinia());
  const router = createTvRouter();
  return mount(SearchPage, {
    global: {
      plugins: [createPinia(), router],
      directives: { tvFocus: {} },
      provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: new BackLayerRegistry(() => undefined) },
    },
  });
}

describe('SearchPage', () => {
  it('remembers the source song row when opening its context menu', async () => {
    installSearchClient();
    const wrapper = mountSearch();
    await wrapper.get('[data-focus-id="search-input"]').setValue('remote');
    await wrapper.get('[data-focus-id="search-submit"]').trigger('click');
    await flushPromises();
    const list = wrapper.getComponent(SongList);
    list.vm.$emit('menu', list.props('tracks')[0]);
    await flushPromises();
    expect(wrapper.getComponent(SongContextMenu).props('sourceFocusId')).toBe('search-song-tv_song_1');
    wrapper.unmount();
  });

  it('keeps the six categories in contract order', () => {
    installSearchClient();
    const wrapper = mountSearch();
    const categories = wrapper.findAll('[data-search-category]').map((node) => node.attributes('data-search-category'));
    expect(categories).toEqual(['song', 'playlist', 'album', 'artist', 'lyric', 'mv']);
  });

  it('renders hot search terms as large focusable rows before any query', async () => {
    installSearchClient();
    const wrapper = mountSearch();
    await flushPromises();
    expect(wrapper.findAll('[data-focus-id^="search-hot-"]')).toHaveLength(2);
    expect(wrapper.text()).toContain('若把你');
  });

  it('submits the query and renders song results', async () => {
    const client = installSearchClient();
    const wrapper = mountSearch();
    const input = wrapper.get('[data-focus-id="search-input"]');
    await input.setValue('若把你');
    await wrapper.get('[data-focus-id="search-submit"]').trigger('click');
    await flushPromises();
    expect(client.request).toHaveBeenCalledWith('content.search', { query: '若把你', category: 'song' });
    expect(wrapper.findAll('[data-focus-id^="search-song-"]').length).toBeGreaterThan(0);
  });

  it('switching category loads that category and keeps its results', async () => {
    installSearchClient();
    const wrapper = mountSearch();
    const input = wrapper.get('[data-focus-id="search-input"]');
    await input.setValue('x');
    await wrapper.get('[data-focus-id="search-submit"]').trigger('click');
    await flushPromises();
    await wrapper.get('[data-search-category="album"]').trigger('click');
    await flushPromises();
    expect(wrapper.findAll('[data-focus-id^="search-album-"]').length).toBeGreaterThan(0);
    expect(wrapper.get('[data-search-category="album"]').attributes('aria-selected')).toBe('true');
  });
});
