type Axis = 'top' | 'left';
type ScrollRun = { owner: HTMLElement; axis: Axis; frame: number; last: number; to: number };
const runs = new Set<ScrollRun>();

export function motionReduced(element: HTMLElement): boolean {
  return Boolean(element.closest('.tv-reduced-motion')) || Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
}

export function cancelScrollMotion(owner?: HTMLElement, axis?: Axis, finish = false): void {
  for (const run of runs) {
    if ((!owner || owner === run.owner) && (!axis || axis === run.axis)) {
      cancelAnimationFrame(run.frame);
      const property = run.axis === 'top' ? 'scrollTop' : 'scrollLeft';
      if (finish && run.owner.isConnected && !document.hidden && Math.abs(run.owner[property] - run.last) <= 1) run.owner[property] = run.to;
      runs.delete(run);
    }
  }
}

export function cancelScrollOutside(target: Node): void {
  for (const run of runs) if (!run.owner.contains(target)) cancelScrollMotion(run.owner);
}

/** One bounded, replaceable run per axis. Never queue animations or delay focus. */
export function scrollToPosition(owner: HTMLElement, axis: Axis, destination: number, animate = false, duration = 150): void {
  cancelScrollMotion(owner, axis);
  if (!Number.isFinite(destination)) return;
  const property = axis === 'top' ? 'scrollTop' : 'scrollLeft';
  const viewport = axis === 'top' ? owner.clientHeight : owner.clientWidth;
  const extent = axis === 'top' ? owner.scrollHeight : owner.scrollWidth;
  const from = owner[property];
  const to = Math.min(Math.max(0, extent - viewport), Math.max(0, destination));
  const delta = to - from;
  const root = owner.closest('.tv-canvas');
  const rapid = root?.classList.contains('tv-rapid-input');
  // Long jumps (restored position / current-song shortcut) must never travel
  // through many pages. Opaque-fallback TVs retain a shorter cheap scroll.
  const time = rapid ? 70 : root?.classList.contains('tv-no-blur') ? Math.min(duration, 120) : duration;
  if (!animate || !root || !owner.isConnected || document.hidden || motionReduced(owner) || viewport <= 0 || Math.abs(delta) > viewport * .85 || Math.abs(delta) < 1) {
    owner[property] = to;
    return;
  }
  const run: ScrollRun = { owner, axis, frame: 0, last: from, to };
  const started = performance.now();
  runs.add(run);
  const step = (now: number) => {
    if (!runs.has(run)) return;
    // A route restore, native scroll or another owner wins over this run.
    if (!owner.isConnected || document.hidden || Math.abs(owner[property] - run.last) > 1) {
      runs.delete(run);
      return;
    }
    if (motionReduced(owner)) { owner[property] = to; runs.delete(run); return; }
    const progress = Math.min(1, Math.max(0, (now - started) / time));
    owner[property] = from + delta * (1 - (1 - progress) ** 3);
    run.last = owner[property];
    if (progress < 1) run.frame = requestAnimationFrame(step);
    else runs.delete(run);
  };
  run.frame = requestAnimationFrame(step);
}
