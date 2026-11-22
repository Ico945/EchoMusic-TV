import { cancelScrollMotion, scrollToPosition } from '../input/scrollMotion';

/** Scroll one owned container only; scrollIntoView may also move hidden canvas ancestors. */
export function scrollWithin(
  target: HTMLElement,
  container: HTMLElement,
  options: { block?: 'nearest' | 'center'; bottomLimit?: number; behavior?: ScrollBehavior; targetRect?: DOMRect; animate?: boolean; duration?: number } = {},
): void {
  if (target === container || !container.contains(target)) return;
  // Also stop a previous target's movement when the new target is visible.
  cancelScrollMotion(container, 'top');
  const area = container.getBoundingClientRect();
  const rect = options.targetRect ?? target.getBoundingClientRect();
  if (area.height <= 0) return;
  const height = container.offsetHeight;
  const renderedScale = height > 0 ? area.height / height : 1;
  const scale = Number.isFinite(renderedScale) && renderedScale > 0 ? renderedScale : 1;
  const top = area.top + 8;
  const bottom = Math.min(area.bottom - 8, options.bottomLimit ?? Infinity);
  if (bottom <= top) return;
  let delta = 0;
  if (options.block === 'center') delta = (rect.top + rect.bottom - top - bottom) / 2;
  else if (rect.top < top && rect.bottom > bottom) return; // Oversized but already spanning the visible area.
  else if (rect.top < top) delta = rect.top - top;
  else if (rect.bottom > bottom) delta = rect.bottom - bottom;
  if (!Number.isFinite(delta) || Math.abs(delta) < 0.5) return;
  const amount = delta > 0 ? Math.ceil(delta / scale) : Math.floor(delta / scale);
  if (options.animate) {
    scrollToPosition(container, 'top', container.scrollTop + amount, true, options.duration);
    return;
  }
  if (typeof container.scrollBy === 'function') container.scrollBy({ top: amount, left: 0, behavior: options.behavior ?? 'auto' });
  else container.scrollTop += amount;
}

/** Nested drawers own their scrolling; never climb beyond the page's content surface. */
export function contentScrollOwner(target: HTMLElement, surface: HTMLElement): HTMLElement {
  for (let parent = target.parentElement; parent && parent !== surface; parent = parent.parentElement) {
    // Horizontal TV shelves also compute overflow-y:auto in Chromium. They
    // must not capture vertical reveal and leave an off-screen row hidden.
    if (/^(auto|scroll)$/.test(getComputedStyle(parent).overflowY) && parent.scrollHeight > parent.clientHeight + 1) return parent;
  }
  return surface;
}
