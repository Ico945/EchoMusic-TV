import { createPinia } from 'pinia';
import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { expect, it, vi } from 'vitest';
import { useTvPlayerStore } from '../stores/player';
import SongList from './SongList.vue';

it('position-only snapshots do not patch 180 song rows, but track and play state changes do', async () => {
  const pinia = createPinia(), player = useTvPlayerStore(pinia);
  const tracks = Array.from({ length: 180 }, (_, i) => ({ id: `r50-${i}`, title: `Song ${i}`, artist: 'Artist', album: '', artwork: '', durationMs: 180000 }));
  const snapshot = (id = 'r50-0', positionMs = 0, isPlaying = true) => ({
    currentTrack: { id, title: id, artists: ['Artist'], album: '', durationMs: 180000, qualities: ['128'] },
    isPlaying, positionMs, durationMs: 180000, queue: [], queueIndex: -1,
    repeatMode: 'off', quality: '128', lyrics: [], error: null, errorRevision: 0,
  });
  expect(player.applySnapshot(snapshot())).toBe(true);
  const updated = vi.fn();
  const wrapper = mount(SongList, { props: { tracks, group: 'perf' }, global: { plugins: [pinia], directives: { tvFocus: { updated } } } });
  try {
    for (let i = 1; i <= 10; i++) { player.applySnapshot(snapshot('r50-0', i * 500)); await nextTick(); }
    expect(updated).not.toHaveBeenCalled();
    player.applySnapshot(snapshot('r50-1')); await nextTick();
    expect(wrapper.get('[aria-current=true]').attributes('data-focus-id')).toBe('perf-r50-1');
    expect(updated).toHaveBeenCalled(); updated.mockClear();
    player.applySnapshot(snapshot('r50-1', 6000, false)); await nextTick();
    expect(updated).toHaveBeenCalled();
  } finally { wrapper.unmount(); }
});
