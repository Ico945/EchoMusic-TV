import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createTestingPinia } from '@pinia/testing';
import { createTvRouter } from '../router';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import SongContextMenu from './SongContextMenu.vue';
import type { TvTrack } from '../stores/app';

const track: TvTrack = {
  id: 'tv_t1',
  title: '蓝色时刻',
  artist: 'Echo Sessions',
  album: 'Blue Hour',
  artwork: '',
  durationMs: 221_000,
};

function mountMenu(registry: BackLayerRegistry, sourceFocusId?: string) {
  return mount(SongContextMenu, {
    props: { track, sourceFocusId },
    global: {
      plugins: [createTvRouter(), createTestingPinia({ createSpy: vi.fn, stubActions: false })],
      directives: { tvFocus: {} },
      provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: registry },
    },
  });
}

describe('SongContextMenu', () => {
  it('renders only the four implemented actions and emits them', async () => {
    const registry = new BackLayerRegistry(() => undefined);
    const wrapper = mountMenu(registry);
    expect(wrapper.findAll('[data-focus-id^="song-menu-"]').map((n) => n.attributes('data-focus-id'))).toEqual([
      'song-menu-favorite',
      'song-menu-play-next',
      'song-menu-detail',
      'song-menu-share',
      'song-menu-close',
    ]);
    await wrapper.get('[data-focus-id="song-menu-share"]').trigger('click');
    expect(wrapper.emitted('action')?.[0]?.[0]).toBe('share');
  });

  it('registers a dialog back layer that closes and restores the source focus id', async () => {
    const restored: string[] = [];
    const registry = new BackLayerRegistry((id) => restored.push(id));
    const wrapper = mountMenu(registry, 'shelf-row-1');

    expect(registry.dispatch()).toBe('dialog');
    expect(restored).toEqual(['shelf-row-1']);
    expect(wrapper.emitted('close')).toBeTruthy();
  });

  it('closing via the close button restores the source focus', async () => {
    const restored: string[] = [];
    const registry = new BackLayerRegistry((id) => restored.push(id));
    const wrapper = mountMenu(registry, 'shelf-row-1');
    await wrapper.get('[data-focus-id="song-menu-close"]').trigger('click');
    expect(restored).toEqual(['shelf-row-1']);
    expect(wrapper.emitted('close')).toBeTruthy();
  });
});
