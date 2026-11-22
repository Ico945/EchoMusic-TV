import { chooseNext } from './geometry';
import type { Direction, FocusBox } from './types';

type RegisteredFocusTarget = {
  id: string;
  group?: string;
  element: HTMLElement;
};

const DIRECTION_OVERRIDE_KEY: Record<Direction, keyof DOMStringMap> = {
  up: 'focusUp',
  down: 'focusDown',
  left: 'focusLeft',
  right: 'focusRight',
};

export class FocusRegistry {
  private readonly targets = new Map<string, RegisteredFocusTarget>();
  private readonly registeredIds = new WeakMap<HTMLElement, string>();

  constructor(private readonly onFocusChanged: (id: string) => void = () => undefined) {}

  private activeModal(): HTMLElement | undefined {
    return Array.from(document.querySelectorAll<HTMLElement>('[aria-modal="true"]')).reverse().find((modal) => {
      for (let node: HTMLElement | null = modal; node; node = node.parentElement) {
        if (node.hidden || node.hasAttribute('inert')) return false;
        const style = getComputedStyle(node);
        if (style.display === 'none' || style.visibility === 'hidden') return false;
      }
      return true;
    });
  }

  /** Consume the first key after a modal opens if its initial focus is not ready. */
  containModalFocus(modal: HTMLElement | null = this.activeModal() ?? null): boolean {
    if (!modal) return false;
    const active = document.activeElement as HTMLElement | null;
    if (active && modal.contains(active) && this.isEligible(active, new Map(), modal)) return false;
    this.restore('');
    return true;
  }

  register(element: HTMLElement): void {
    const id = element.dataset.focusId;
    const previousId = this.registeredIds.get(element);
    if (!id) {
      this.unregister(element);
      return;
    }

    if (previousId === id) {
      const target = this.targets.get(id);
      if (target?.element === element) target.group = element.dataset.focusGroup;
      return;
    }

    if (previousId) this.removeTarget(element, previousId);

    this.targets.set(id, { id, group: element.dataset.focusGroup, element });
    this.registeredIds.set(element, id);
  }

  unregister(element: HTMLElement): void {
    const id = this.registeredIds.get(element);
    if (!id) return;

    this.removeTarget(element, id);
    this.registeredIds.delete(element);
  }

  move(direction: Direction, scope?: HTMLElement): boolean {
    const modal = this.activeModal() ?? null;
    if (this.containModalFocus(modal)) return true;
    const active = document.activeElement as HTMLElement | null;
    const originId = active ? this.registeredIds.get(active) : undefined;
    const origin = originId ? this.targets.get(originId) : undefined;
    // Scope cached ancestor styles to this synchronous read phase. Reusing
    // them across key presses could navigate behind a newly opened dialog.
    const visibility = new Map<HTMLElement, boolean>();
    if (!origin || origin.element !== active || !this.isEligible(origin.element, visibility, modal)) return false;

    const overrideId = origin.element.dataset[DIRECTION_OVERRIDE_KEY[direction]];
    const override = overrideId ? this.targets.get(overrideId) : undefined;
    // Browse zones must not jump diagonally into global chrome. Vertical
    // self-links at a page boundary yield to the shell's explicit zone bridge.
    const withinScope = (target: RegisteredFocusTarget) => !scope || scope.contains(target.element);
    const verticalSelf = scope && override === origin && (direction === 'up' || direction === 'down');
    if (override && !verticalSelf && withinScope(override) && this.isEligible(override.element, visibility, modal)) return this.focus(override);

    // At a declared list/grid boundary, no item in that group can be the
    // next vertical target. Avoid reading hundreds of off-screen rectangles.
    const outsideGroup = origin.group && ((direction === 'up' && origin.element.dataset.focusBoundaryUp === 'true')
      || (direction === 'down' && origin.element.dataset.focusBoundaryDown === 'true'));
    const next = chooseNext(
      this.toFocusBox(origin),
      [...this.targets.values()]
        .filter((target) => target !== origin && (!outsideGroup || target.group !== origin.group) && withinScope(target) && this.isEligible(target.element, visibility, modal))
        .map((target) => this.toFocusBox(target)),
      direction,
    );

    return next ? this.focus(this.targets.get(next.id)!) : false;
  }

  restore(id: string): boolean {
    const modal = this.activeModal() ?? null;
    const visibility = new Map<HTMLElement, boolean>();
    const requested = this.targets.get(id);
    const entries = [...this.targets.values()];
    const page = !modal ? document.querySelector<HTMLElement>('.content-surface [data-page]') : null;
    const preferred = page?.dataset.defaultFocusId ? this.targets.get(page.dataset.defaultFocusId) : undefined;
    const local = preferred && this.isEligible(preferred.element, visibility, modal) ? preferred
      : entries.find(({ element }) => page?.contains(element) && this.isEligible(element, visibility, modal));
    const selectedNav = entries.find(({ element }) => element.getAttribute('aria-current') === 'page' && this.isEligible(element, visibility, modal));
    const target =
      requested && this.isEligible(requested.element, visibility, modal)
        ? requested
        : local ?? selectedNav ?? entries.find(({ element }) => this.isEligible(element, visibility, modal));

    return target ? this.focus(target) : false;
  }

  // All callers validate in this same synchronous pass. Rechecking here forces
  // another ancestor-style walk; never reuse eligibility across separate keys.
  private focus(target: RegisteredFocusTarget): boolean {
    target.element.focus({ preventScroll: true });
    this.onFocusChanged(target.id);
    return true;
  }

  private toFocusBox(target: RegisteredFocusTarget): FocusBox {
    const { left, top, right, bottom } = target.element.getBoundingClientRect();
    return {
      id: target.id,
      left,
      top,
      right,
      bottom,
      centerX: (left + right) / 2,
      centerY: (top + bottom) / 2,
    };
  }

  private isEligible(element: HTMLElement, visibility = new Map<HTMLElement, boolean>(), modal: HTMLElement | null = this.activeModal() ?? null): boolean {
    if (modal && !modal.contains(element)) return false;
    if (
      !element.isConnected ||
      element.hidden ||
      element.hasAttribute('disabled') ||
      element.getAttribute('aria-hidden') === 'true' ||
      element.getAttribute('aria-disabled') === 'true' ||
      element.matches(':disabled')
    ) {
      return false;
    }

    const inspected: HTMLElement[] = [];
    let visible = true;
    for (let ancestor: HTMLElement | null = element; ancestor; ancestor = ancestor.parentElement) {
      const cached = visibility.get(ancestor);
      if (cached !== undefined) {
        visible = cached;
        break;
      }
      inspected.push(ancestor);
      if (ancestor.hasAttribute('inert')) {
        visible = false;
        break;
      }
      const style = getComputedStyle(ancestor);
      if (style.display === 'none' || style.visibility === 'hidden') {
        visible = false;
        break;
      }
    }
    inspected.forEach((ancestor) => visibility.set(ancestor, visible));
    return visible;
  }

  private removeTarget(element: HTMLElement, id: string): void {
    if (this.targets.get(id)?.element === element) this.targets.delete(id);
  }
}
