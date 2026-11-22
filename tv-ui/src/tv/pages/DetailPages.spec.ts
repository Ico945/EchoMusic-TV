import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import { createTvRouter } from '../router';
import PlaylistDetailPage from './PlaylistDetailPage.vue';
import AlbumDetailPage from './AlbumDetailPage.vue';

const playlistDetail = {
  id: '1',
  title: '夜晚歌单',
  kind: 'playlist',
  artwork: '',
  creator: { id: 'u1', nickname: '夜航员', avatar: '', summary: '' },
  playCount: 100,
  tags: ['夜晚'],
  introduction: '为深夜准备的声音。',
  trackCount: 2,
  tabs: ['songs', 'comments', 'introduction'],
  tracks: {
    items: [
      { id: 'tv_t1', title: '蓝色时刻', artist: 'Echo Sessions', album: 'Blue Hour', artwork: '', durationMs: 221000 },
      { id: 'tv_t2', title: '城市微光', artist: '晚风电台', album: '夜航', artwork: '', durationMs: 198000 },
    ],
    nextCursor: null,
    total: 2,
  },
};

const albumDetail = {
  id: '1',
  title: '夜航',
  kind: 'album',
  artwork: '',
  artist: '晚风电台',
  publishDate: '2026-08-01',
  introduction: '',
  tabs: ['songs', 'comments', 'introduction'],
  tracks: {
    items: [],
    nextCursor: null,
    total: null,
  },
};

const albumTracks = {
  items: [{ id: 'tv_t2', title: '城市微光', artist: '晚风电台', album: '夜航', artwork: '', durationMs: 198000 }],
  nextCursor: null,
  total: 1,
};

function installDetailClient(detail: unknown, children: unknown = { items: [], nextCursor: null, total: 0 }) {
  const client: NativeClientLike = {
    request: vi.fn((method: string) => {
      if (method === 'content.detail') return Promise.resolve(detail);
      if (method === 'content.comments') {
        return Promise.resolve({ items: [], nextCursor: null, total: 0 });
      }
      if (method === 'content.detailChildren') return Promise.resolve(children);
      if (method === 'library.favorite') {
        return Promise.resolve({ kind: 'playlist', id: 'playlist:1', favorite: true });
      }
      if (method === 'share.create') {
        return Promise.resolve({
          link: 'https://www.kugou.com/yy/special/single/1',
          qrImageDataUrl: 'data:image/png;base64,REAL_QR',
          clipboardAvailable: false,
        });
      }
      return Promise.resolve({});
    }) as NativeClientLike['request'],
    on: () => () => undefined,
  };
  installNativeClientForTest(client);
  return client;
}

async function mountAt(component: unknown, path: string, backLayers: BackLayerRegistry) {
  setActivePinia(createPinia());
  const router = createTvRouter();
  await router.push(path);
  await router.isReady();
  return mount(component as never, {
    global: {
      plugins: [createPinia(), router],
      directives: { tvFocus: {} },
      provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: backLayers },
    },
  });
}

describe('DetailPages', () => {
  it('personal playlists go directly from play all to songs without an empty tab step', async () => {
    installDetailClient({ ...playlistDetail, id: 'library_1' });
    const wrapper = await mountAt(PlaylistDetailPage, '/playlist/playlist:library_1', new BackLayerRegistry(() => undefined));
    await flushPromises();
    expect(wrapper.find('[data-focus-id=playlist-tab-songs]').exists()).toBe(false);
    expect(wrapper.get('[data-focus-id=detail-primary]').attributes('data-focus-down')).toBe('playlist-tv_t1');
    expect(wrapper.get('[data-focus-id=playlist-tv_t1]').attributes('data-focus-up')).toBe('detail-primary');
    expect(wrapper.get('[data-focus-id=playlist-tv_t1]').attributes('data-focus-down')).toBe('playlist-tv_t2');
    expect(wrapper.get('[data-focus-id=playlist-tv_t2]').attributes('data-focus-left')).toBe('nav-library');
    wrapper.unmount();
  });
  it('renders playlist songs and the approved tabs', async () => {
    installDetailClient(playlistDetail);
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = await mountAt(PlaylistDetailPage, '/playlist/playlist:1', registry);
    await flushPromises();
    expect(wrapper.text()).toContain('夜晚歌单');
    expect(wrapper.findAll('[data-focus-id^="playlist-tab-"]').map((node) => node.attributes('data-focus-id'))).toEqual([
      'playlist-tab-songs',
      'playlist-tab-comments',
      'playlist-tab-introduction',
    ]);
    expect(wrapper.findAll('[data-focus-id^="playlist-tv_"]')).toHaveLength(2);
  });

  it('switches to the comments tab and loads comments', async () => {
    const client = installDetailClient(playlistDetail);
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = await mountAt(PlaylistDetailPage, '/playlist/playlist:1', registry);
    await flushPromises();
    await wrapper.get('[data-focus-id="playlist-tab-comments"]').trigger('click');
    await flushPromises();
    expect(client.request).toHaveBeenCalledWith('content.comments', { kind: 'playlist', id: 'playlist:1' });
    expect(wrapper.text()).toContain('还没有评论');
  });

  it('opens a real share projection from the playlist hero', async () => {
    const client = installDetailClient(playlistDetail);
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = await mountAt(PlaylistDetailPage, '/playlist/playlist:1', registry);
    await flushPromises();

    await wrapper.get('[data-focus-id="detail-share"]').trigger('click');
    await flushPromises();

    expect(client.request).toHaveBeenCalledWith('share.create', { kind: 'playlist', id: 'playlist:1' });
    expect(wrapper.get('[role="dialog"][aria-label="分享"] img').attributes('src')).toBe('data:image/png;base64,REAL_QR');
  });

  it('renders album detail with the artist and publish date', async () => {
    const client = installDetailClient(albumDetail, albumTracks);
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = await mountAt(AlbumDetailPage, '/album/album:1', registry);
    await flushPromises();
    expect(wrapper.text()).toContain('夜航');
    expect(wrapper.text()).toContain('晚风电台');
    expect(wrapper.findAll('[data-focus-id^="album-tv_"]')).toHaveLength(1);
    expect(client.request).toHaveBeenCalledWith('content.detailChildren', { kind: 'album', id: 'album:1', tab: 'songs' });
  });
});
