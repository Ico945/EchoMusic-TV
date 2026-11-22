import { afterEach, describe, expect, it } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import { tvFocusRegistry, vTvFocus } from '../focus/directive';
import DetailHero from './DetailHero.vue';

let wrapper: VueWrapper | undefined;
afterEach(() => { wrapper?.unmount(); wrapper = undefined; });
function setup(artwork = '') {
  wrapper = mount(DetailHero, { attachTo: document.body, props: { title: 'Fixture', subtitle: 'Artist', meta: '3:20', primaryLabel: '播放', artwork },
    global: { directives: { tvFocus: vTvFocus } } });
  return wrapper;
}
describe('Detail artwork and focus', () => {
  it('renders the supplied cover without adding a focus stop', () => {
    const view = setup('https://imge.kugou.com/400/fixture.jpg');
    expect(view.get('img').attributes('src')).toBe('https://imge.kugou.com/400/fixture.jpg');
    expect(view.get('img').attributes('alt')).toBe('');
    expect(view.findAll('[data-focus-id]')).toHaveLength(3);
  });
  it('retains the placeholder when artwork is absent', () => { const view = setup(); expect(view.find('img').exists()).toBe(false); expect(view.get('.hero-art svg').attributes('data-icon')).toBe('music'); });
  it('falls back after a failed image and retries a different cover', async () => {
    const view = setup('https://imge.kugou.com/400/old.jpg'); await view.get('img').trigger('error');
    expect(view.find('img').exists()).toBe(false); expect(view.get('.hero-art svg').attributes('data-icon')).toBe('music');
    await view.setProps({ artwork: 'https://imge.kugou.com/400/new.jpg' }); expect(view.get('img').attributes('src')).toContain('/new.jpg');
  });
  it('ignores an old images late error after the source changes', async () => {
    const view = setup('https://imge.kugou.com/400/old.jpg'); const old = view.get('img');
    await view.setProps({ artwork: 'https://imge.kugou.com/400/new.jpg' }); await old.trigger('error');
    expect(view.get('img').attributes('src')).toContain('/new.jpg');
  });
  it('keeps the active playback action focused through artwork updates', async () => {
    const view = setup(); tvFocusRegistry.restore('detail-primary'); const button = document.activeElement;
    await view.setProps({ artwork: 'https://imge.kugou.com/400/new.jpg' }); await view.get('img').trigger('error');
    expect(document.activeElement).toBe(button); expect((button as HTMLElement).dataset.focusId).toBe('detail-primary');
  });
});
