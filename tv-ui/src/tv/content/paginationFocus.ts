import { nextTick, onBeforeUnmount, ref, watch } from 'vue';

/** Keep the initiating control mounted until its own content can accept focus. */
export function usePaginationFocus(options: {
  scope: () => HTMLElement | null;
  itemFocusIds: () => readonly string[];
  loading: () => boolean;
  error: () => boolean;
  hasMore: () => boolean;
  context?: () => unknown;
  fallbackFocusId?: () => string | undefined;
}) {
  const retainAction = ref(false);
  let request: { source: HTMLElement; previous: Set<string> } | undefined;

  function cancel(): void {
    request = undefined;
    retainAction.value = false;
    document.removeEventListener('focusin', movedFocus, true);
  }

  function movedFocus(event: FocusEvent): void {
    // Moving away and then returning is still a newer choice by the viewer.
    if (event.target !== request?.source) cancel();
  }

  function finish(): void {
    const pending = request;
    if (!pending || options.loading()) return;
    const scope = options.scope();
    if (!scope?.isConnected || !scope.contains(pending.source) || document.activeElement !== pending.source) {
      cancel();
      return;
    }
    if (options.error()) { cancel(); return; }
    const ids = options.itemFocusIds();
    const added = ids.find(id => !pending.previous.has(id));
    if (!added && options.hasMore()) { cancel(); return; }
    const targetId = added ?? ids[ids.length - 1] ?? options.fallbackFocusId?.();
    const target = Array.from(scope.querySelectorAll<HTMLElement>('[data-focus-id]'))
      .find(node => node.dataset.focusId === targetId);
    // A dialog may be mounted before its initial focus callback runs.
    if (document.querySelector('[aria-modal="true"]')) { cancel(); return; }
    if (target) {
      // Direct, scoped focus never falls back to a globally registered player.
      // Shell focusin handling reveals this row using the existing scroll owner.
      target.focus({ preventScroll: true });
      if (document.activeElement === target) cancel();
    }
    // An empty terminal page without a valid local fallback keeps an accessible
    // completion control until the viewer moves away.
  }

  function begin(source: HTMLElement | null): void {
    cancel();
    if (!source || document.activeElement !== source || !options.scope()?.contains(source)) return;
    request = { source, previous: new Set(options.itemFocusIds()) };
    retainAction.value = true;
    document.addEventListener('focusin', movedFocus, true);
    // Schedule after the click handler has emitted its request. A nextTick
    // captured here can still belong to the preceding failure's render.
    const pending = request;
    queueMicrotask(() => void nextTick(() => { if (request === pending) finish(); }));
  }

  watch([options.loading, options.error, options.hasMore, options.itemFocusIds], finish, { flush: 'post' });
  if (options.context) watch(options.context, cancel, { flush: 'sync' });
  onBeforeUnmount(cancel);
  return { retainAction, begin };
}
