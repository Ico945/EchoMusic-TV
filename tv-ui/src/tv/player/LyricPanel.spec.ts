import { enableAutoUnmount, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import LyricPanel from './LyricPanel.vue';

enableAutoUnmount(afterEach);

const lines = [
  { startMs: 0, endMs: 999, text: '第一句' },
  { startMs: 1_000, endMs: 1_999, text: '第二句' },
  { startMs: 2_000, endMs: 2_999, text: '第三句' },
];

describe('LyricPanel', () => {
  afterEach(() => vi.useRealTimers());

  it('holds manual browsing for three seconds and then returns to the native active line', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(5_000);
    const wrapper = mount(LyricPanel, { props: { lines, activeIndex: 0 } });

    await wrapper.trigger('keydown', { key: 'ArrowDown' });
    expect(wrapper.get('[data-lyric-active="true"]').text()).toBe('第二句');
    await wrapper.setProps({ activeIndex: 2 });
    await vi.advanceTimersByTimeAsync(2_999);
    expect(wrapper.get('[data-lyric-active="true"]').text()).toBe('第二句');

    await vi.advanceTimersByTimeAsync(1);
    expect(wrapper.get('[data-lyric-active="true"]').text()).toBe('第三句');
    expect(wrapper.find('.manual-hint').exists()).toBe(false);
  });

  it('keeps vertical lyric browsing while exposing horizontal exits from the panel', () => {
    const wrapper = mount(LyricPanel, { props: { lines, activeIndex: 0 } });

    expect(wrapper.attributes('data-focus-left')).toBe('player-toggle');
    expect(wrapper.attributes('data-focus-right')).toBe('mode-lyrics');
  });
});
