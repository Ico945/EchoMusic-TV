import { mount } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { tvFocusRegistry, vTvFocus } from './directive';
import { FocusRegistry } from './registry';

const rect = (left: number, top: number, width = 100, height = 60): DOMRect =>
  ({
    left,
    top,
    right: left + width,
    bottom: top + height,
    width,
    height,
    x: left,
    y: top,
    toJSON: () => ({}),
  }) as DOMRect;

const focusable = (id: string, left: number, top: number, tag = 'button'): HTMLElement => {
  const element = document.createElement(tag);
  element.dataset.focusId = id;
  element.getBoundingClientRect = () => rect(left, top);
  document.body.append(element);
  return element;
};

const remove = (...elements: HTMLElement[]) => elements.forEach((element) => element.remove());

type IneligibleCandidate = {
  description: string;
  tag?: string;
  prepare: (candidate: HTMLElement) => HTMLElement[];
};

const ineligibleCandidates: IneligibleCandidate[] = [
  {
    description: 'hidden',
    prepare: (candidate) => {
      candidate.hidden = true;
      return [];
    },
  },
  {
    description: 'native disabled',
    prepare: (candidate) => {
      (candidate as HTMLButtonElement).disabled = true;
      return [];
    },
  },
  {
    description: 'non-native disabled',
    tag: 'div',
    prepare: (candidate) => {
      candidate.tabIndex = 0;
      candidate.setAttribute('disabled', '');
      return [];
    },
  },
  {
    description: 'aria-disabled',
    prepare: (candidate) => {
      candidate.setAttribute('aria-disabled', 'true');
      return [];
    },
  },
  {
    description: 'display-none',
    prepare: (candidate) => {
      candidate.style.display = 'none';
      return [];
    },
  },
  {
    description: 'visibility-hidden',
    prepare: (candidate) => {
      candidate.style.visibility = 'hidden';
      return [];
    },
  },
  {
    description: 'display-none ancestor',
    prepare: (candidate) => {
      const ancestor = document.createElement('div');
      ancestor.style.display = 'none';
      candidate.remove();
      ancestor.append(candidate);
      document.body.append(ancestor);
      return [ancestor];
    },
  },
  {
    description: 'visibility-hidden ancestor',
    prepare: (candidate) => {
      const ancestor = document.createElement('div');
      ancestor.style.visibility = 'hidden';
      candidate.remove();
      ancestor.append(candidate);
      document.body.append(ancestor);
      return [ancestor];
    },
  },
  {
    description: 'disconnected',
    prepare: (candidate) => {
      candidate.remove();
      return [];
    },
  },
];

describe('FocusRegistry', () => {
  it('keeps shared-ancestor style reads linear for a 400-row list', () => {
    const registry = new FocusRegistry();
    const outer = document.createElement('section');
    document.body.append(outer);
    let shelf = outer;
    for (let depth = 0; depth < 6; depth += 1) {
      const inner = document.createElement('div');
      shelf.append(inner);
      shelf = inner;
    }
    const rows = Array.from({ length: 400 }, (_, index) => {
      const row = focusable(`row-${index}`, 0, index * 80);
      shelf.append(row);
      registry.register(row);
      return row;
    });
    rows[0].focus();
    const styles = vi.spyOn(window, 'getComputedStyle');
    try {
      expect(registry.move('down')).toBe(true);
      expect(document.activeElement).toBe(rows[1]);
      // One read per row plus a small allowance for ancestor/final-focus
      // validation, independent of row count times nesting depth.
      expect(styles.mock.calls.length).toBeLessThan(440);
    } finally {
      styles.mockRestore();
      outer.remove();
    }
  });

  it.each(['inert', 'display', 'visibility'] as const)('rechecks changed %s ancestors on the next key press', (mode) => {
    const registry = new FocusRegistry();
    const origin = focusable('origin', 0, 0);
    const target = focusable('target', 140, 0);
    const fallback = focusable('fallback', 280, 0);
    const shelf = document.createElement('section');
    shelf.append(target);
    document.body.append(shelf);
    [origin, target, fallback].forEach((element) => registry.register(element));
    try {
      origin.focus();
      expect(registry.move('right')).toBe(true);
      expect(document.activeElement).toBe(target);
      origin.focus();
      if (mode === 'inert') shelf.setAttribute('inert', '');
      if (mode === 'display') shelf.style.display = 'none';
      if (mode === 'visibility') shelf.style.visibility = 'hidden';
      expect(registry.move('right')).toBe(true);
      expect(document.activeElement).toBe(fallback);
      shelf.removeAttribute('inert');
      shelf.style.cssText = '';
      origin.focus();
      expect(registry.move('right')).toBe(true);
      expect(document.activeElement).toBe(target);
    } finally { remove(origin, fallback, shelf); }
  });

  it('does not treat the replaced element of a duplicate id as the active origin', () => {
    const registry = new FocusRegistry();
    const old = focusable('same', 0, 0);
    const replacement = focusable('same', 0, 80);
    const target = focusable('target', 140, 0);
    [old, replacement, target].forEach((element) => registry.register(element));
    try {
      old.focus();
      expect(registry.move('right')).toBe(false);
      expect(document.activeElement).toBe(old);
    } finally { remove(old, replacement, target); }
  });

  it('moves actual DOM focus using the target geometry and reports the logical id (catches missing focus side effect)', () => {
    const changed: string[] = [];
    const registry = new FocusRegistry((id) => changed.push(id));
    const origin = focusable('origin', 0, 0);
    const target = focusable('target', 140, 0);
    registry.register(origin);
    registry.register(target);
    origin.focus();

    registry.move('right');

    expect(document.activeElement).toBe(target);
    expect(changed).toEqual(['target']);
    remove(origin, target);
  });

  it('honors an explicit directional boundary before geometric navigation', () => {
    const registry = new FocusRegistry();
    const origin = focusable('origin', 300, 100);
    const geometric = focusable('geometric', 160, 100);
    const explicit = focusable('explicit', 20, 260);
    origin.dataset.focusLeft = 'explicit';
    registry.register(origin);
    registry.register(geometric);
    registry.register(explicit);
    origin.focus();

    registry.move('left');

    expect(document.activeElement).toBe(explicit);
    remove(origin, geometric, explicit);
  });

  it('passes preventScroll while moving DOM focus (catches a focus call without scroll protection)', () => {
    const registry = new FocusRegistry();
    const origin = focusable('origin', 0, 0);
    const target = focusable('target', 140, 0);
    const originalFocus = target.focus.bind(target);
    let focusOptions: FocusOptions | undefined;
    target.focus = (options?: FocusOptions) => {
      focusOptions = options;
      originalFocus();
    };
    registry.register(origin);
    registry.register(target);
    origin.focus();

    registry.move('right');

    expect(focusOptions).toEqual({ preventScroll: true });
    expect(document.activeElement).toBe(target);
    remove(origin, target);
  });

  it.each(ineligibleCandidates)(
    'skips $description candidates (catches incomplete eligibility filtering)',
    ({ tag, prepare }) => {
      const registry = new FocusRegistry();
      const origin = focusable('origin', 0, 0);
      const candidate = focusable('candidate', 120, 0, tag);
      const target = focusable('target', 160, 0);
      const containers = prepare(candidate);
      registry.register(origin);
      registry.register(candidate);
      registry.register(target);
      origin.focus();

      registry.move('right');

      expect(document.activeElement).toBe(target);
      remove(origin, candidate, target, ...containers);
    },
  );

  it('ignores elements missing a logical id (catches registration of unregistered elements)', () => {
    const registry = new FocusRegistry();
    const origin = focusable('origin', 0, 0);
    const unnamed = document.createElement('button');
    unnamed.getBoundingClientRect = () => rect(140, 0);
    document.body.append(unnamed);
    registry.register(origin);
    registry.register(unnamed);
    origin.focus();

    registry.move('right');

    expect(document.activeElement).toBe(origin);
    remove(origin, unnamed);
  });

  it('uses a replacement element when an id is registered twice (catches replacement rejection)', () => {
    const registry = new FocusRegistry();
    const origin = focusable('origin', 0, 0);
    const first = focusable('target', 140, 0);
    const duplicate = focusable('target', 140, 80);
    registry.register(origin);
    registry.register(first);
    registry.register(duplicate);
    origin.focus();

    registry.move('right');

    expect(document.activeElement).toBe(duplicate);
    remove(origin, first, duplicate);
  });

  it('keeps a replacement registered after the old element unregisters (catches old-unmount cleanup)', () => {
    const registry = new FocusRegistry();
    const origin = focusable('origin', 0, 0);
    const old = focusable('target', 120, 0);
    const replacement = focusable('target', 140, 0);
    registry.register(origin);
    registry.register(old);
    registry.register(replacement);
    registry.unregister(old);
    origin.focus();

    registry.move('right');

    expect(document.activeElement).toBe(replacement);
    remove(origin, old, replacement);
  });

  it('unregisters by its original id after a data-focus-id change (catches mutable-attribute cleanup)', () => {
    const registry = new FocusRegistry();
    const origin = focusable('origin', 0, 0);
    const target = focusable('old-id', 140, 0);
    registry.register(origin);
    registry.register(target);
    target.dataset.focusId = 'new-id';
    registry.register(target);
    registry.unregister(target);
    origin.focus();

    registry.move('right');

    expect(document.activeElement).toBe(origin);
    remove(origin, target);
  });

  it('restores a visible target and falls back to the first eligible target (catches missing restore fallback)', () => {
    const registry = new FocusRegistry();
    const fallback = focusable('fallback', 0, 0);
    const unavailable = focusable('unavailable', 140, 0);
    const target = focusable('target', 280, 0);
    unavailable.hidden = true;
    registry.register(fallback);
    registry.register(unavailable);
    registry.register(target);

    registry.restore('target');
    expect(document.activeElement).toBe(target);

    registry.restore('unavailable');
    expect(document.activeElement).toBe(fallback);
    remove(fallback, unavailable, target);
  });

  it('does nothing when move has no registered active origin or eligible candidate (catches an invalid fallback focus)', () => {
    const registry = new FocusRegistry();
    const registered = focusable('registered', 0, 0);
    const disabled = focusable('disabled', 140, 0);
    const outsider = document.createElement('button');
    (disabled as HTMLButtonElement).disabled = true;
    document.body.append(outsider);
    registry.register(registered);
    registry.register(disabled);
    outsider.focus();

    registry.move('right');
    expect(document.activeElement).toBe(outsider);

    registered.focus();
    registry.move('right');
    expect(document.activeElement).toBe(registered);
    remove(registered, disabled, outsider);
  });

  it('removes an explicitly unregistered target from navigation (catches missing unregister cleanup)', () => {
    const registry = new FocusRegistry();
    const origin = focusable('origin', 0, 0);
    const target = focusable('target', 140, 0);
    registry.register(origin);
    registry.register(target);
    registry.unregister(target);
    origin.focus();

    registry.move('right');

    expect(document.activeElement).toBe(origin);
    remove(origin, target);
  });

  it('does not move into an inert focus scope', () => {
    const registry = new FocusRegistry();
    const origin = focusable('origin', 0, 0);
    const scope = document.createElement('section');
    const target = focusable('target', 140, 0);
    scope.setAttribute('inert', '');
    scope.append(target);
    document.body.append(scope);
    registry.register(origin);
    registry.register(target);
    origin.focus();

    registry.move('right');

    expect(document.activeElement).toBe(origin);
    remove(origin, scope);
  });
});

describe('v-tv-focus', () => {
  it('registers a mounted target for D-pad navigation (catches a missing mounted hook)', () => {
    const Host = defineComponent({
      template: '<button v-tv-focus data-focus-id="directive-target">Target</button>',
    });
    const wrapper = mount(Host, {
      attachTo: document.body,
      global: { directives: { tvFocus: vTvFocus } },
    });
    const origin = focusable('directive-origin', 0, 0);
    const target = wrapper.get('button').element as HTMLButtonElement;
    target.getBoundingClientRect = () => rect(140, 0);
    tvFocusRegistry.register(origin);
    origin.focus();

    tvFocusRegistry.move('right');

    expect(document.activeElement).toBe(target);
    tvFocusRegistry.unregister(origin);
    wrapper.unmount();
    remove(origin);
  });

  it('unregisters on unmount so a replacement with the same id can navigate (catches a directive lifecycle leak)', async () => {
    const Host = defineComponent({
      template: '<button v-tv-focus data-focus-id="directive-target">Target</button>',
    });
    const wrapper = mount(Host, {
      attachTo: document.body,
      global: { directives: { tvFocus: vTvFocus } },
    });
    wrapper.unmount();
    await nextTick();

    const origin = focusable('directive-origin', 0, 0);
    const replacement = focusable('directive-target', 140, 0);
    tvFocusRegistry.register(origin);
    tvFocusRegistry.register(replacement);
    origin.focus();

    tvFocusRegistry.move('right');

    expect(document.activeElement).toBe(replacement);
    tvFocusRegistry.unregister(origin);
    tvFocusRegistry.unregister(replacement);
    remove(origin, replacement);
  });

  it('updates registration when a mounted element changes its logical id (catches a missing updated hook)', async () => {
    const Host = defineComponent({
      data: () => ({ focusId: 'old-id' }),
      template: '<button v-tv-focus :data-focus-id="focusId">Target</button>',
    });
    const wrapper = mount(Host, {
      attachTo: document.body,
      global: { directives: { tvFocus: vTvFocus } },
    });
    await wrapper.setData({ focusId: 'new-id' });
    wrapper.unmount();
    await nextTick();

    const origin = focusable('directive-origin', 0, 0);
    const replacement = focusable('old-id', 140, 0);
    tvFocusRegistry.register(origin);
    tvFocusRegistry.register(replacement);
    origin.focus();

    tvFocusRegistry.move('right');

    expect(document.activeElement).toBe(replacement);
    tvFocusRegistry.unregister(origin);
    tvFocusRegistry.unregister(replacement);
    remove(origin, replacement);
  });
});
