import { createPinia } from 'pinia';
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { BackLayerRegistry, BACK_LAYER_REGISTRY_KEY } from '../input/backCoordinator';
import { createTvRouter } from '../router';

const baseTrack = {
  id: 'tv_real_track',
  title: 'Real Song',
  artist: 'Real Artist',
  album: 'Real Album',
  artwork: 'real-artwork',
  durationMs: 180_000,
};

describe('HomePage native MV entry', () => {
  beforeEach(() => {
    window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  });

  afterEach(() => delete window.EchoTvNative);

  it('navigates only a native-provided typed MV and records the real source card focus id', async () => {
    const home = {
      greeting: '晚上好',
      hero: baseTrack,
      secondary: [],
      shelves: [{
        id: 'native-shelf',
        title: 'Native',
        capability: 'play',
        tracks: [
          { ...baseTrack, id: 'tv_without_mv' },
          {
            ...baseTrack,
            id: 'tv_with_mv',
            mv: { id: 'mv:9001', title: 'Real MV', artist: 'Real Artist', quality: 'hd', qualities: ['hd', 'fhd'] },
          },
        ],
      }],
    };
    const request = vi.fn((method: string) => {
      if (method === 'app.getSnapshot') return Promise.resolve({ route: 'home', session: { status: 'loggedIn' }, bootstrapHome: home });
      if (method === 'content.home') return Promise.resolve(home);
      if (method === 'video.snapshot') return Promise.resolve({ durationMs: 0, positionMs: 0, playing: true, quality: 'hd', errorCode: null, open: true, operation: null });
      if (method === 'video.close') return Promise.resolve({ accepted: true });
      return Promise.resolve({ accepted: true, quality: 'hd' });
    });
    installNativeClientForTest({ request: request as NativeClientLike['request'], on: () => () => undefined });
    const router = createTvRouter();
    await router.push('/home');
    await router.isReady();
    const restored: string[] = [];
    const registry = new BackLayerRegistry((id) => restored.push(id));
    const wrapper = mount({ template: '<RouterView />' }, {
      global: {
        plugins: [createPinia(), router],
        directives: { tvFocus: {} },
        provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: registry },
      },
    });
    await flushPromises();

    const entries = wrapper.findAll('[data-mv-entry]');
    const cardSlot = wrapper.get('.music-card-slot').element as HTMLElement;
    expect(wrapper.get('[data-page="home"]').classes()).toContain('core-browse-page');
    expect(wrapper.findAll('.tv-card').length).toBeGreaterThan(0);
    expect(getComputedStyle(cardSlot).display).toBe('flex');
    expect(getComputedStyle(cardSlot).flexDirection).toBe('column');
    expect(getComputedStyle(cardSlot).gap).toBe('14px');
    expect(wrapper.get('.music-card').attributes('data-focus-left')).toBe('nav-home');
    expect(entries).toHaveLength(1);
    expect(entries[0].attributes('data-focus-id')).toBe('mv-native-shelf-tv_with_mv');
    await entries[0].trigger('click');
    await flushPromises();

    expect(router.currentRoute.value.name).toBe('mv-detail');
    expect(router.currentRoute.value.params.id).toBe('mv:9001');
    expect(router.currentRoute.value.query).toMatchObject({
      title: 'Real MV',
      artist: 'Real Artist',
      quality: 'hd',
      qualities: 'hd,fhd',
      sourceFocusId: 'mv-native-shelf-tv_with_mv',
    });

    await wrapper.get('[data-focus-id="mv-close"]').trigger('click');
    await flushPromises();
    expect(router.currentRoute.value.name).toBe('home');
    expect(restored).toEqual(['mv-native-shelf-tv_with_mv']);
  });
});
