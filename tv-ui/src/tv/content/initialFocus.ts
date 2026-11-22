import { onBeforeUnmount } from 'vue';
import { tvFocusRegistry } from '../focus/directive';
import { mayAutoRestorePageFocus } from './routeMemory';

/** One-shot focus transfer after initial data has rendered. Call in setup. */
export function useInitialFocusRestore(navFocusId: string): (id: string) => boolean {
  let pending = true;
  let viewerInteracted = false;
  const claimFocus = () => { viewerInteracted = true; };
  // Capture also sees keys consumed by an input, overlay or nested control.
  window.addEventListener('keydown', claimFocus, true);
  const dispose = () => {
    pending = false;
    window.removeEventListener('keydown', claimFocus, true);
  };
  onBeforeUnmount(dispose);

  return (id) => {
    if (!pending) return false;
    dispose();
    if (viewerInteracted || !mayAutoRestorePageFocus(navFocusId)) return false;
    return tvFocusRegistry.restore(id);
  };
}
