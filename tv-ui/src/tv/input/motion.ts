import { isTextEditingTarget, remoteEventTarget, REMOTE_DIRECTIONS } from './remoteKeys';
import { cancelScrollMotion, cancelScrollOutside } from './scrollMotion';

// Purely visual: never consume, throttle or replay a remote command.
export function installTvMotion(root: HTMLElement): () => void {
  let lastDirectionAt = -Infinity;
  let settleTimer: ReturnType<typeof setTimeout> | undefined;
  const reset = () => {
    clearTimeout(settleTimer);
    if (root.classList.contains('tv-rapid-input')) root.classList.remove('tv-rapid-input');
    delete root.dataset.remoteNavigating;
    lastDirectionAt = -Infinity;
  };
  const onKey = (event: KeyboardEvent) => {
    if (!REMOTE_DIRECTIONS[event.key] || isTextEditingTarget(remoteEventTarget(event))) {
      reset();
      // Confirm/Back can capture a route origin immediately. Settle the
      // selected target first so returning never restores it off screen.
      cancelScrollMotion(undefined, undefined, true);
      return;
    }
    root.dataset.remoteNavigating = 'true';
    const now = Date.now();
    if ((event.repeat || now - lastDirectionAt < 110) && !root.classList.contains('tv-rapid-input')) root.classList.add('tv-rapid-input');
    lastDirectionAt = now;
    clearTimeout(settleTimer);
    settleTimer = setTimeout(reset, 160);
  };
  window.addEventListener('keydown', onKey, true);
  const suspend = () => { reset(); cancelScrollMotion(); };
  const visibility = () => { if (document.hidden) suspend(); };
  const focus = (event: FocusEvent) => { if (event.target instanceof Node) cancelScrollOutside(event.target); };
  window.addEventListener('blur', suspend);
  window.addEventListener('focusin', focus);
  document.addEventListener('visibilitychange', visibility);
  return () => {
    window.removeEventListener('keydown', onKey, true);
    window.removeEventListener('blur', suspend);
    window.removeEventListener('focusin', focus);
    document.removeEventListener('visibilitychange', visibility);
    suspend();
  };
}
