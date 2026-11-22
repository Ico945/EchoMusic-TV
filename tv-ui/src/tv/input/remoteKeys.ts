import type { Direction } from '../focus/types';

export const REMOTE_DIRECTIONS: Record<string, Direction> = {
  ArrowUp: 'up',
  ArrowDown: 'down',
  ArrowLeft: 'left',
  ArrowRight: 'right',
};

const ENTER_ALIASES = new Set(['Enter', 'NumpadEnter', 'Select', 'Accept']);
const BACK_ALIASES = new Set(['Backspace', 'Escape', 'GoBack', 'BrowserBack']);

export function normalizeRemoteKey(key: string): string {
  if (ENTER_ALIASES.has(key)) return 'Enter';
  if (BACK_ALIASES.has(key)) return 'GoBack';
  if (key === 'MediaNextTrack') return 'MediaTrackNext';
  if (key === 'MediaPreviousTrack') return 'MediaTrackPrevious';
  return key;
}

export function remoteEventTarget(event: KeyboardEvent): HTMLElement | null {
  if (event.target instanceof HTMLElement) return event.target;
  return document.activeElement instanceof HTMLElement ? document.activeElement : null;
}

export function isTextEditingTarget(target: HTMLElement | null): boolean {
  if (!target) return false;
  if (target instanceof HTMLTextAreaElement) return !target.disabled && !target.readOnly;
  if (target instanceof HTMLInputElement) {
    return !target.disabled && !target.readOnly && !['button', 'checkbox', 'radio', 'range', 'submit'].includes(target.type);
  }
  return Boolean(target.isContentEditable);
}
