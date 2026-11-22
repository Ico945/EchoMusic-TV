const savedFocus = new Map<string, string>();

// Async page data may arrive after the viewer has moved to another navigation
// item, the MiniPlayer or a dialog. Only an unclaimed focus or this page's own
// navigation entry may be transferred automatically into newly loaded content.
export function mayAutoRestorePageFocus(navFocusId: string): boolean {
  const active = document.activeElement as HTMLElement | null;
  const id = active?.dataset.focusId;
  return !id || id === navFocusId;
}

export function rememberFocus(key: string): void {
  const element = document.activeElement as HTMLElement | null;
  const page = element?.closest<HTMLElement>('[data-page]')
    ?? document.querySelector<HTMLElement>('.content-surface > [data-page]');
  if (!page) return;
  const id = element && page.contains(element) && !element.closest('[role=dialog]')
    ? element.dataset.focusId : page.dataset.lastContentFocus;
  // Navigation, dock and modal focus are not a page's return position.
  if (id && Array.from(page.querySelectorAll<HTMLElement>('[data-focus-id]'))
    .some(node => node.dataset.focusId === id && !node.closest('[role=dialog]'))) savedFocus.set(key, id);
}

export function recallFocus(key: string): string | undefined {
  return savedFocus.get(key);
}

export function sourceFocusFromEvent(event: Event): string | undefined {
  const target = event.currentTarget as HTMLElement | null;
  return target?.dataset.focusId ?? undefined;
}
