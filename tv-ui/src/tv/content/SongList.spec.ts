import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { enableAutoUnmount, mount } from '@vue/test-utils';
import { createTestingPinia } from '@pinia/testing';
import { createTvRouter } from '../router';
import SongList from './SongList.vue';
import type { TvTrack } from '../stores/app';

const track = (id: string): TvTrack => ({
  id,
  title: `${id} title`,
  artist: `${id} artist`,
  album: `${id} album`,
  artwork: '',
  durationMs: 200_000,
});

enableAutoUnmount(afterEach);

function mountList(tracks: TvTrack[]) {
  return mount(SongList, {
    props: { tracks, group: 'list-test' },
    global: {
      plugins: [createTvRouter(), createTestingPinia({ createSpy: vi.fn, stubActions: false })],
      directives: { tvFocus: {} },
    },
  });
}

describe('SongList confirm-hold interaction', () => {
  it.each(['blur', 'native-cancel', 'window-blur', 'remove', 'unmount'])('cancels a held row on %s', async (cause) => {
    const wrapper = mountList([track('t1'), track('t2')]);
    const row = wrapper.get('[data-focus-id="list-test-t1"]');
    await row.trigger('keydown', { key: 'Enter' });
    if (cause === 'blur') await row.trigger('blur');
    if (cause === 'native-cancel') window.dispatchEvent(new Event('echo-tv-input-cancel'));
    if (cause === 'window-blur') window.dispatchEvent(new Event('blur'));
    if (cause === 'remove') await wrapper.setProps({ tracks: [track('t2')] });
    if (cause === 'unmount') wrapper.unmount();
    vi.advanceTimersByTime(1000);
    if (cause !== 'remove' && cause !== 'unmount') {
      await row.trigger('keydown', { key: 'Enter', repeat: true });
      await row.trigger('keyup', { key: 'Enter' });
    }
    expect(wrapper.emitted('play')).toBeUndefined();
    expect(wrapper.emitted('menu')).toBeUndefined();
  });

  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('a quick Enter press runs the row primary action (play)', async () => {
    const wrapper = mountList([track('t1')]);
    const row = wrapper.get('[data-focus-id="list-test-t1"]');
    await row.trigger('keydown', { key: 'Enter' });
    await row.trigger('keyup', { key: 'Enter' });
    const played = wrapper.emitted('play')?.[0]?.[0] as { id: string } | undefined;
    expect(played?.id).toBe('t1');
    expect(wrapper.emitted('menu')).toBeUndefined();
  });

  it('holding Enter for 650ms opens the context menu exactly once', async () => {
    const wrapper = mountList([track('t1')]);
    const row = wrapper.get('[data-focus-id="list-test-t1"]');
    await row.trigger('keydown', { key: 'Enter' });
    vi.advanceTimersByTime(650);
    await row.trigger('keyup', { key: 'Enter' });
    const menu = wrapper.emitted('menu')?.[0]?.[0] as { id: string } | undefined;
    expect(menu?.id).toBe('t1');
    expect(wrapper.emitted('menu')).toHaveLength(1);
    expect(wrapper.emitted('play')).toBeUndefined();
  });

  it('ignores repeated keydown events while a press is in flight', async () => {
    const wrapper = mountList([track('t1')]);
    const row = wrapper.get('[data-focus-id="list-test-t1"]');
    await row.trigger('keydown', { key: 'Enter' });
    await row.trigger('keydown', { key: 'Enter' });
    vi.advanceTimersByTime(650);
    await row.trigger('keyup', { key: 'Enter' });
    expect(wrapper.emitted('menu')).toHaveLength(1);
  });

  it('renders the empty text without rows', () => {
    const wrapper = mountList([]);
    expect(wrapper.text()).toContain('暂无歌曲');
    expect(wrapper.findAll('[data-focus-id]')).toHaveLength(0);
  });
});
