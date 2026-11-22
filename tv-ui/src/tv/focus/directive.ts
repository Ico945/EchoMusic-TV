import type { ObjectDirective } from 'vue';
import { FocusRegistry } from './registry';

export const tvFocusRegistry = new FocusRegistry();

export const vTvFocus: ObjectDirective<HTMLElement> = {
  mounted(element) {
    tvFocusRegistry.register(element);
  },
  updated(element) {
    tvFocusRegistry.register(element);
  },
  unmounted(element) {
    tvFocusRegistry.unregister(element);
  },
};
