import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { createTestingPinia } from '@pinia/testing';
import { vi } from 'vitest';
import { createTvRouter } from '../router';
import ContentGrid from './ContentGrid.vue';
import type { TvContentCard } from '../native/contentTypes';

const card = (id: string): TvContentCard => ({
  id,
  title: `${id} title`,
  kind: 'playlist',
  subtitle: 'sub',
  artwork: '',
});

function mountGrid(cards: TvContentCard[], leftFocusId?: string) {
  return mount(ContentGrid, {
    props: { cards, group: 'grid-test', leftFocusId },
    global: {
      plugins: [createTvRouter(), createTestingPinia({ createSpy: vi.fn, stubActions: false })],
      directives: { tvFocus: {} },
    },
  });
}

describe('ContentGrid', () => {
  it('reaches the nearest last-row card before leaving a partially filled grid', () => {
    const wrapper = mountGrid(Array.from({ length: 7 }, (_, index) => card(`playlist:${index}`)));
    expect(wrapper.get('[data-focus-id=grid-test-4]').attributes('data-focus-down')).toBe('grid-test-6');
    expect(wrapper.get('[data-focus-id=grid-test-4]').attributes('data-focus-boundary-down')).toBeUndefined();
    expect(wrapper.get('[data-focus-id=grid-test-5]').attributes('data-focus-boundary-down')).toBe('true');
  });
  it('renders stable per-index focus ids', () => {
    const wrapper = mountGrid([card('playlist:1'), card('playlist:2')]);
    const ids = wrapper.findAll('[data-focus-id]').map((node) => node.attributes('data-focus-id'));
    expect(ids).toEqual(['grid-test-0', 'grid-test-1']);
    expect(wrapper.findAll('.tv-card')).toHaveLength(2);
  });

  it('binds every first-column card to the requested left boundary', () => {
    const wrapper = mountGrid(Array.from({ length: 6 }, (_, index) => card(`playlist:${index}`)), 'nav-explore');
    const edges = wrapper.findAll('[data-focus-left="nav-explore"]').map((node) => node.attributes('data-focus-id'));
    expect(edges).toEqual(['grid-test-0', 'grid-test-5']);
  });

  it('uses four-column boundaries and deterministic row movement when requested', async () => {
    const wrapper = mountGrid(Array.from({ length: 8 }, (_, index) => card(`playlist:${index}`)), 'nav-explore');
    await wrapper.setProps({ columns: 4, upFocusId: 'explore-tab-newPlaylists' });
    expect(wrapper.get('[data-focus-id=grid-test-4]').attributes('data-focus-left')).toBe('nav-explore');
    expect(wrapper.get('[data-focus-id=grid-test-3]').attributes('data-focus-right')).toBe('grid-test-3');
    expect(wrapper.get('[data-focus-id=grid-test-0]').attributes('data-focus-down')).toBe('grid-test-4');
    expect(wrapper.get('[data-focus-id=grid-test-0]').attributes('data-focus-up')).toBe('explore-tab-newPlaylists');
  });

  it('emits select with the clicked card', async () => {
    const wrapper = mountGrid([card('playlist:1')]);
    await wrapper.get('[data-focus-id="grid-test-0"]').trigger('click');
    const selected = wrapper.emitted('select')?.[0]?.[0] as { id: string } | undefined;
    expect(selected?.id).toBe('playlist:1');
  });

  it('shows the empty text when there are no cards', () => {
    const wrapper = mountGrid([]);
    expect(wrapper.text()).toContain('暂无内容');
  });
});
