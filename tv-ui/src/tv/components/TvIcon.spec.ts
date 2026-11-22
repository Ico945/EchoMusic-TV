import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import TvIcon from './TvIcon.vue';
import { TV_ICON_PATHS, TV_SOLID_ICONS } from './icons';

describe('TV icon system', () => {
  it.each(Object.keys(TV_ICON_PATHS))('%s renders a static, inherited-paint glyph without a focus stop', name => {
    const wrapper = mount(TvIcon, { props: { name, size: 32 } });
    const svg = wrapper.get('svg');
    expect(svg.attributes()).toMatchObject({ 'data-icon': name, width: '32', height: '32', viewBox: '0 0 24 24', 'aria-hidden': 'true', focusable: 'false', 'stroke-width': '1.8', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' });
    expect(wrapper.findAll('path').map(path => path.attributes('d'))).toEqual(TV_ICON_PATHS[name as keyof typeof TV_ICON_PATHS]);
    expect(wrapper.findAll('text, use, image, filter, animate, animateTransform, [tabindex], [data-focus-id], style')).toHaveLength(0);
    const solid = TV_SOLID_ICONS.has(name as keyof typeof TV_ICON_PATHS);
    expect(svg.attributes('fill')).toBe(solid ? 'currentColor' : 'none');
    expect(svg.attributes('stroke')).toBe(solid ? 'none' : 'currentColor');
    wrapper.unmount();
  });

  it('favorite changes silhouette, not size or color, and reverses when unselected', async () => {
    const wrapper = mount(TvIcon, { props: { name: 'heart', size: 26 } });
    const path = wrapper.get('path').attributes('d');
    expect(wrapper.attributes('fill')).toBe('none');
    await wrapper.setProps({ active: true });
    expect(wrapper.attributes()).toMatchObject({ fill: 'currentColor', stroke: 'none', width: '26', height: '26' });
    expect(wrapper.get('path').attributes('d')).toBe(path);
    await wrapper.setProps({ active: false });
    expect(wrapper.attributes('fill')).toBe('none');
    wrapper.unmount();
  });

  it.each(['missing-icon', '__proto__', 'constructor'])('unknown glyph %s safely falls back to music', name => {
    const wrapper = mount(TvIcon, { props: { name } });
    expect(wrapper.attributes('data-icon')).toBe('music');
    expect(wrapper.findAll('path')).toHaveLength(TV_ICON_PATHS.music.length);
    wrapper.unmount();
  });

  it('every static TV icon consumer has a registered glyph', () => {
    const pages = import.meta.glob('../**/*.vue', { query: '?raw', import: 'default', eager: true });
    for (const [file, text] of Object.entries(pages)) {
      for (const match of String(text).matchAll(/<TvIcon\s[^>]*?(?<!:)\bname="([^"]+)"/g)) {
        expect(Object.prototype.hasOwnProperty.call(TV_ICON_PATHS, match[1]), file + ': ' + match[1]).toBe(true);
      }
    }
    expect(TV_ICON_PATHS.queue).not.toEqual(TV_ICON_PATHS.playNext);
    expect(TV_ICON_PATHS.repeat).not.toEqual(TV_ICON_PATHS.repeatOne);
  });
});
