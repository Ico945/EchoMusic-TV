import { mount } from '@vue/test-utils';
import { defineComponent, nextTick, ref } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import TvOverlayTransition from './TvOverlayTransition.vue';
import { FocusRegistry } from '../focus/registry';

afterEach(() => { vi.useRealTimers(); document.body.replaceChildren(); });
it('retires a closing modal immediately, before the visual exit finishes', async () => {
  vi.useFakeTimers();
  const open = ref(true);
  const wrapper = mount(defineComponent({ components: { TvOverlayTransition }, setup: () => ({ open }),
    template: '<main><button data-focus-id="source">Source</button><TvOverlayTransition><aside v-if="open"><section aria-modal="true"><button data-focus-id="modal">Modal</button></section></aside></TvOverlayTransition></main>',
  }), { attachTo: document.body, global: { stubs: { transition: false } } });
  const registry = new FocusRegistry();
  const source = wrapper.get('[data-focus-id=source]').element as HTMLElement;
  const modal = wrapper.get('[data-focus-id=modal]').element as HTMLElement;
  registry.register(source); registry.register(modal); registry.restore('modal');
  open.value = false; await nextTick();
  expect(wrapper.get('aside').attributes('inert')).toBeDefined();
  expect(registry.restore('source')).toBe(true); expect(document.activeElement).toBe(source);
  await vi.advanceTimersByTimeAsync(250); expect(wrapper.find('aside').exists()).toBe(false);
  open.value = true; await nextTick();
  expect(wrapper.get('aside').attributes('inert')).toBeUndefined();
  wrapper.unmount();
});
