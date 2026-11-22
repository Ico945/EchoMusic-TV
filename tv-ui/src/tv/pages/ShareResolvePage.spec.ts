import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import { createTvRouter } from '../router';
import ShareResolvePage from './ShareResolvePage.vue';

function installResolveClient(result: () => Promise<unknown>): NativeClientLike {
  const client: NativeClientLike = {
    request: vi.fn((method: string) => {
      if (method === 'share.resolve') return result();
      return Promise.resolve({});
    }) as NativeClientLike['request'],
    on: () => () => undefined,
  };
  installNativeClientForTest(client);
  return client;
}

async function mountResolvePage() {
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createTvRouter();
  router.addRoute({ path: '/share-resolve', name: 'share-resolve', component: ShareResolvePage });
  await router.push('/share-resolve');
  await router.isReady();
  const wrapper = mount(ShareResolvePage, {
    global: {
      plugins: [pinia, router],
      directives: { tvFocus: {} },
      provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: new BackLayerRegistry(() => undefined) },
    },
  });
  return { wrapper, router };
}

describe('ShareResolvePage', () => {
  it('shows an error and does not navigate for an invalid link', async () => {
    installResolveClient(() => Promise.reject(new Error('invalid link')));
    const { wrapper, router } = await mountResolvePage();

    await wrapper.get('[data-focus-id="share-resolve-input"]').setValue('not-a-real-share-url');
    await wrapper.get('[data-focus-id="share-resolve-submit"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('链接无效');
    expect(router.currentRoute.value.name).toBe('share-resolve');
  });

  it('navigates to the detail route for a valid link', async () => {
    installResolveClient(() => Promise.resolve({ kind: 'song', id: 'tv_song_1' }));
    const { wrapper, router } = await mountResolvePage();

    await wrapper.get('[data-focus-id="share-resolve-input"]').setValue('https://example.com/share/abc');
    await wrapper.get('[data-focus-id="share-resolve-submit"]').trigger('click');
    await flushPromises();

    expect(router.currentRoute.value.name).toBe('song-detail');
  });
});
