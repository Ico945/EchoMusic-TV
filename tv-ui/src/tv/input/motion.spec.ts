import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installTvMotion } from './motion';

describe('TV visual motion budget', () => {
  let root: HTMLElement;
  let release: () => void;
  const key = (name = 'ArrowDown', repeat = false, target: EventTarget = window) => {
    const event = new KeyboardEvent('keydown', { key: name, repeat, bubbles: true, cancelable: true });
    target.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
  };
  beforeEach(() => {
    vi.useFakeTimers();
    root = document.createElement('main');
    document.body.append(root);
    release = installTvMotion(root);
  });
  afterEach(() => { release(); root.remove(); vi.useRealTimers(); });

  it('marks single and burst navigation without consuming any remote key', () => {
    key();
    expect(root.classList.contains('tv-rapid-input')).toBe(false);
    expect(root.dataset.remoteNavigating).toBe('true');
    vi.advanceTimersByTime(90); key();
    expect(root.classList.contains('tv-rapid-input')).toBe(true);
    vi.advanceTimersByTime(150); key('ArrowRight', true);
    expect(root.classList.contains('tv-rapid-input')).toBe(true);
    vi.advanceTimersByTime(160);
    expect(root.classList.contains('tv-rapid-input')).toBe(false);
    key();
    expect(root.classList.contains('tv-rapid-input')).toBe(false);
  });

  it('leaves text editing and confirmation entirely alone', () => {
    const input = document.createElement('input');
    root.append(input);
    key('ArrowDown', true, input);
    key('Enter', true);
    expect(root.classList.contains('tv-rapid-input')).toBe(false);
    expect(root.dataset.remoteNavigating).toBeUndefined();
  });

  it('cleans timers and listeners on blur and unmount', () => {
    key('ArrowDown', true);
    window.dispatchEvent(new Event('blur'));
    expect(root.classList.contains('tv-rapid-input')).toBe(false);
    key('ArrowRight', true); release();
    expect(vi.getTimerCount()).toBe(0);
    key('ArrowRight', true);
    expect(root.classList.contains('tv-rapid-input')).toBe(false);
  });
});
