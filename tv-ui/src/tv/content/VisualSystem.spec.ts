import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import TvIcon from '../components/TvIcon.vue';
import { TV_ICON_PATHS } from '../components/icons';
import { TV_NAV_ITEMS } from '../router';
import ContentSkeleton from './ContentSkeleton.vue';
import RankingArtwork from './RankingArtwork.vue';

describe('TV outline icons and geometric loading states', () => {
  it('all navigation icons belong to the same non-focusable SVG family', () => {
    for (const item of TV_NAV_ITEMS) {
      expect(TV_ICON_PATHS[item.icon]).toBeTruthy();
      const wrapper = mount(TvIcon, { props: { name: item.icon } });
      expect(wrapper.get('svg').attributes()).toMatchObject({ viewBox: '0 0 24 24', 'stroke-width': '1.8', 'aria-hidden': 'true', focusable: 'false' });
      expect(wrapper.findAll('path').length).toBeGreaterThan(0);
      wrapper.unmount();
    }
  });
  it.each(['cards', 'songs', 'terms', 'shelf'] as const)('%s skeletons provide real layout slots without remote focus stops', variant => {
    const wrapper = mount(ContentSkeleton, { props: { variant, count: 4, label: '正在加载' } });
    expect(wrapper.findAll('.skeleton-item')).toHaveLength(4);
    expect(wrapper.findAll('[data-focus-id],button,input,[tabindex]')).toHaveLength(0);
    expect(wrapper.get('[role=status]').attributes('aria-label')).toBe('正在加载');
    expect(wrapper.findAll('.skeleton-art')).toHaveLength(variant === 'terms' ? 0 : 4);
    wrapper.unmount();
  });
  it('failed ranking covers fall back to SVG and retry when the artwork changes', async () => {
    const wrapper = mount(RankingArtwork, { props: { artwork: 'https://imge.kugou.com/a.jpg' } });
    await wrapper.get('img').trigger('error');
    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.get('svg').attributes('data-icon')).toBe('chart');
    await wrapper.setProps({ artwork: 'https://imge.kugou.com/b.jpg' });
    expect(wrapper.get('img').attributes('src')).toContain('/b.jpg');
    wrapper.unmount();
  });
});
