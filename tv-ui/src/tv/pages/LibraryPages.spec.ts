import { afterEach, describe, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { createPinia, getActivePinia, setActivePinia } from 'pinia';
import type { Component } from 'vue';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import { createTvRouter } from '../router';
import { useTvAppStore } from '../stores/app';
import { useTvLibraryStore } from '../stores/library';
import { tvFocusRegistry } from '../focus/directive';
import LibraryPage from './LibraryPage.vue';
import CloudPage from './CloudPage.vue';
import FavoritesPage from './FavoritesPage.vue';

enableAutoUnmount(afterEach);
afterEach(() => delete window.EchoTvNative);

function installLibraryClient(): NativeClientLike {
  const client: NativeClientLike = {
    request: vi.fn((method: string, payload: unknown) => {
      const record = payload as Record<string, unknown> | undefined;
      if (method === 'library.playlists') return Promise.resolve({ items: [], nextCursor: null, total: null });
      if (method === 'library.get') {
        return Promise.resolve({
          counts: {
            favoriteSongs: 3,
            history: 5,
            cloud: 2,
            purchasedSongs: 1,
            purchasedAlbums: 1,
            createdPlaylists: 1,
            favoritePlaylists: 2,
          },
          featured: [],
        });
      }
      if (method === 'library.section') {
        if (record?.section === 'cloud') {
          return Promise.resolve({
            section: 'cloud',
            items: [
              { id: 'cloud:aa1', title: '云端歌曲一', kind: 'song', subtitle: '歌手一', artwork: '' },
              { id: 'cloud:bb2', title: '云端歌曲二', kind: 'song', subtitle: '歌手二', artwork: '' },
            ],
            nextCursor: null,
            total: 2,
          });
        }
        return Promise.resolve({ section: record?.section, items: [], nextCursor: null, total: 0 });
      }
      if (method === 'library.deleteCloudItem') {
        return Promise.resolve({});
      }
      return Promise.resolve({});
    }) as NativeClientLike['request'],
    on: () => () => undefined,
  };
  installNativeClientForTest(client);
  return client;
}

function mountPage(component: Component, reuse = false) {
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  const pinia = reuse ? getActivePinia()! : createPinia();
  setActivePinia(pinia);
  useTvAppStore().session = { status: 'loggedIn', displayName: '测试用户', membership: 'VIP' };
  const router = createTvRouter();
  return mount(component, {
    attachTo: document.body,
    global: {
      plugins: [pinia, router],
      directives: { tvFocus: {} },
      provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: new BackLayerRegistry(() => undefined) },
    },
  });
}

describe('LibraryPage', () => {
  it('reloads after restored account metadata invalidates the library without a status transition', async () => {
    const client = installLibraryClient();
    const wrapper = mountPage(LibraryPage);
    await flushPromises();
    vi.mocked(client.request).mockClear();
    useTvAppStore().session = { status: 'loggedIn', displayName: 'Restored account label' };
    await flushPromises();
    expect(client.request).toHaveBeenCalledWith('library.playlists', {});
    expect(wrapper.text()).not.toContain('正在同步你的歌单');
  });
  it('shows failure instead of claiming an empty collection', async () => {
    const client = installLibraryClient();
    vi.mocked(client.request).mockRejectedValue({ code: 'NETWORK_TIMEOUT' });
    const wrapper = mountPage(LibraryPage);
    await flushPromises();
    expect(wrapper.text()).toContain('歌单同步失败');
    expect(wrapper.text()).not.toContain('还没有收藏歌单');
    expect(wrapper.get('[data-focus-id=library-action]').text()).toBe('重试同步');
  });
  it('places expired-session QR login on the explicit remote focus path', async () => {
    const client = installLibraryClient();
    vi.mocked(client.request).mockRejectedValue({ code: 'AUTH_EXPIRED' });
    const wrapper = mountPage(LibraryPage);
    await flushPromises();
    expect(wrapper.get('[data-focus-id=library-tab-allPlaylists]').attributes('data-focus-down')).toBe('library-relogin');
    expect(wrapper.get('[data-focus-id=library-relogin]').text()).toContain('扫码');
    expect(wrapper.get('[data-focus-id=library-action]').attributes('data-focus-up')).toBe('library-relogin');
  });
  it('opens all playlists without ownership filtering or an aggregate summary', async () => {
    const client = installLibraryClient();
    const wrapper = mountPage(LibraryPage);
    await flushPromises();

    const order = wrapper.findAll('[data-library-section]').map((node) => node.attributes('data-library-section'));
    expect(order).toEqual(['allPlaylists', 'favoritePlaylists', 'createdPlaylists']);
    expect(wrapper.get('[data-page="library"]').classes()).toContain('core-browse-page');
    expect(client.request).toHaveBeenCalledWith('library.playlists', {});
    expect(client.request).not.toHaveBeenCalledWith('library.get', expect.anything());
    expect(wrapper.text()).toContain('暂未同步到歌单');
    expect(wrapper.find('[data-focus-id="profile-logout"]').exists()).toBe(false);
  });

  it('preserves later pages when returning from a playlist without re-fetching page one', async () => {
    const client = installLibraryClient();
    const first = mountPage(LibraryPage);
    await flushPromises();
    const store = useTvLibraryStore();
    store.sections.allPlaylists.items = Array.from({ length: 35 }, (_, index) => ({ id: 'p' + index, title: '歌单 ' + index, kind: 'playlist', subtitle: '', artwork: '' }));
    store.sections.allPlaylists.nextCursor = '3';
    first.unmount();
    vi.mocked(client.request).mockClear();
    const returned = mountPage(LibraryPage, true);
    await flushPromises();
    expect(returned.findAll('.playlist-card')).toHaveLength(35);
    expect(returned.find('[data-focus-id=library-card-p34]').exists()).toBe(true);
    expect(store.sections.allPlaylists.nextCursor).toBe('3');
    expect(client.request).not.toHaveBeenCalled();
  });

  it('places focus on the first newly appended playlist after load more', async () => {
    const client = installLibraryClient();
    const card = (id: string) => ({ id, title: id, kind: 'playlist', subtitle: '', artwork: '' });
    vi.mocked(client.request).mockResolvedValueOnce({ items: [card('a')], nextCursor: '2' }).mockResolvedValueOnce({ items: [card('b'), card('c')], nextCursor: '3' });
    const wrapper = mountPage(LibraryPage);
    await flushPromises();
    const action = wrapper.get('[data-focus-id=library-action]');
    (action.element as HTMLElement).focus();
    await action.trigger('click');
    await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('library-card-b');
    expect(wrapper.findAll('.playlist-card')).toHaveLength(3);
  });

  it('shows safe sync counts instead of concluding the account has no collection', async () => {
    const client = installLibraryClient();
    vi.mocked(client.request).mockResolvedValue({ items: [], nextCursor: null, librarySync: { page: 1, received: 3, playlists: 0, created: 0, collected: 0, albums: 3, missingCreator: 0 } });
    const wrapper = mountPage(LibraryPage);
    await flushPromises();
    expect(wrapper.find('[data-library-diagnostics]').exists()).toBe(false);
    await wrapper.get('[data-focus-id="library-sync-info"]').trigger('click');
    expect(wrapper.get('[data-library-diagnostics]').text()).toContain('接口返回 3 项');
    expect(wrapper.text()).not.toContain('还没有收藏歌单');
  });
});

describe('CloudPage', () => {
  it('does not call deleteCloudItem until the confirmation is confirmed', async () => {
    const client = installLibraryClient();
    const wrapper = mountPage(CloudPage);
    await flushPromises();

    const deleteButtons = wrapper.findAll('[data-action="delete-cloud-item"]');
    expect(deleteButtons.length).toBe(2);

    await deleteButtons[0].trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-action="confirm-delete"]').exists()).toBe(true);
    expect(client.request).not.toHaveBeenCalledWith('library.deleteCloudItem', expect.anything());

    await wrapper.get('[data-action="confirm-delete"]').trigger('click');
    await flushPromises();

    expect(client.request).toHaveBeenCalledWith('library.deleteCloudItem', expect.objectContaining({ trackId: 'cloud:aa1' }));
  });
});

describe('FavoritesPage session and real error states', () => {
  it('routes down from the favorites tab to the first song, not the sidebar', async () => {
    const client = installLibraryClient();
    vi.mocked(client.request).mockResolvedValue({ items: [{ id: 'tv_liked_first', title: 'Liked', kind: 'song', subtitle: 'Artist', artwork: '' }], nextCursor: '2', total: 594 });
    const wrapper = mountPage(FavoritesPage);
    await flushPromises();
    expect(wrapper.get('[data-focus-id=favorites-tab-songs]').attributes('data-focus-down')).toBe('favorites-songs-tv_liked_first');
    expect(wrapper.get('[data-focus-id=favorites-songs-tv_liked_first]').attributes('data-focus-up')).toBe('favorites-tab-songs');
    expect(wrapper.text()).toContain('共 594 首');
    expect(wrapper.text()).toContain('已加载 1 首');
  });

  it('loads after QR login completes on an already mounted page', async () => {
    const client = installLibraryClient();
    const wrapper = mountPage(FavoritesPage);
    await flushPromises();
    const app = useTvAppStore();
    app.session = { status: 'loggedOut' };
    await flushPromises();
    vi.mocked(client.request).mockClear();
    app.session = { status: 'loggedIn', displayName: 'Restored' };
    await flushPromises();
    expect(client.request).toHaveBeenCalledWith('library.section', { section: 'favoriteSongs' });
    expect(wrapper.find('[data-focus-id=favorites-sync]').exists()).toBe(true);
  });

  it('retains an actionable not-recognized state instead of telling the user they have zero likes', async () => {
    const client = installLibraryClient();
    vi.mocked(client.request).mockRejectedValue({ code: 'LIBRARY_LIKED_LIST_MISSING' });
    const wrapper = mountPage(FavoritesPage);
    await flushPromises();
    expect(wrapper.text()).toContain('未识别到喜欢歌单');
    expect(wrapper.text()).toContain('LIBRARY_LIKED_LIST_MISSING');
    expect(wrapper.text()).not.toContain('列表为空');
    expect(wrapper.find('[data-focus-id=favorites-tab-users]').exists()).toBe(false);
  });

  it('refreshes an empty cached section when reopening favorites', async () => {
    const client = installLibraryClient();
    const wrapper = mountPage(FavoritesPage);
    await flushPromises();
    await wrapper.get('[data-focus-id=favorites-sync]').trigger('click');
    await flushPromises();
    expect(vi.mocked(client.request).mock.calls.filter(call => call[0] === 'library.section')).toHaveLength(2);
  });
});
