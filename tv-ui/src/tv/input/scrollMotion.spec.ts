import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cancelScrollMotion, cancelScrollOutside, scrollToPosition } from './scrollMotion';
import { scrollWithin } from '../content/scrollVisibility';

describe('bounded TV scrolling', () => {
  let root: HTMLElement, owner: HTMLElement, target: HTMLElement;
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
    root = document.createElement('main'); root.className = 'tv-canvas';
    owner = document.createElement('section'); target = document.createElement('button');
    root.append(owner); owner.append(target); document.body.append(root);
    Object.defineProperties(owner, {
      clientHeight: { value: 400 }, scrollHeight: { value: 2000 },
      clientWidth: { value: 400 }, scrollWidth: { value: 2000 }, offsetHeight: { value: 400 },
    });
    owner.getBoundingClientRect = () => new DOMRect(0, 0, 400, 400);
  });
  afterEach(() => { cancelScrollMotion(); root.remove(); vi.restoreAllMocks(); vi.useRealTimers(); });
  const advance = (ms: number) => vi.advanceTimersByTime(ms);

  it('moves through intermediate positions and settles exactly within one short run', () => {
    scrollToPosition(owner, 'top', 240, true);
    expect(owner.scrollTop).toBe(0);
    advance(64); expect(owner.scrollTop).toBeGreaterThan(0); expect(owner.scrollTop).toBeLessThan(240);
    advance(112); expect(owner.scrollTop).toBe(240); expect(vi.getTimerCount()).toBe(0);
  });
  it('replaces a target immediately when the remote reverses, without a queued tail', () => {
    scrollToPosition(owner, 'top', 300, true); advance(48);
    const at = owner.scrollTop;
    root.classList.add('tv-rapid-input'); scrollToPosition(owner, 'top', 20, true);
    advance(32); expect(owner.scrollTop).toBeLessThan(at);
    advance(48); expect(owner.scrollTop).toBe(20);
    advance(1000); expect(owner.scrollTop).toBe(20); expect(vi.getTimerCount()).toBe(0);
  });
  it('cancels the old reveal when the next target is already visible', () => {
    scrollToPosition(owner, 'top', 240, true); advance(32);
    const at = owner.scrollTop;
    target.getBoundingClientRect = () => new DOMRect(0, 40, 100, 50);
    scrollWithin(target, owner, { animate: true }); advance(300);
    expect(owner.scrollTop).toBe(at);
  });
  it('does not override external scroll restoration', () => {
    scrollToPosition(owner, 'top', 240, true); advance(32);
    owner.scrollTop = 700; advance(300); expect(owner.scrollTop).toBe(700);
  });
  it('snaps long jumps and clamps the destination', () => {
    scrollToPosition(owner, 'top', 5000, true); expect(owner.scrollTop).toBe(1600);
    scrollToPosition(owner, 'top', -10, true); expect(owner.scrollTop).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('honors reduced motion even during rapid input', () => {
    root.classList.add('tv-reduced-motion', 'tv-rapid-input');
    scrollToPosition(owner, 'top', 240, true); expect(owner.scrollTop).toBe(240);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('shortens opaque-fallback motion without eliminating all continuity', () => {
    root.classList.add('tv-no-blur'); scrollToPosition(owner, 'left', 240, true, 220);
    advance(32); expect(owner.scrollLeft).toBeGreaterThan(0); expect(owner.scrollLeft).toBeLessThan(240);
    advance(96); expect(owner.scrollLeft).toBe(240);
  });
  it('stops on background entry or unmount', () => {
    scrollToPosition(owner, 'top', 240, true); advance(32); const at = owner.scrollTop;
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    advance(200); expect(owner.scrollTop).toBe(at); expect(vi.getTimerCount()).toBe(0);
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
    scrollToPosition(owner, 'top', 300, true); owner.remove(); advance(200);
    expect(owner.scrollTop).toBe(at); expect(vi.getTimerCount()).toBe(0);
  });
  it('stops when focus moves out to chrome but not within the owned container', () => {
    scrollToPosition(owner, 'top', 240, true); cancelScrollOutside(target); advance(32);
    expect(owner.scrollTop).toBeGreaterThan(0); const at = owner.scrollTop;
    cancelScrollOutside(root); advance(200); expect(owner.scrollTop).toBe(at);
  });
  it('keeps vertical and horizontal runs independent and cleans both on cancel', () => {
    scrollToPosition(owner, 'top', 240, true); scrollToPosition(owner, 'left', 180, true);
    advance(48); expect(owner.scrollTop).toBeGreaterThan(0); expect(owner.scrollLeft).toBeGreaterThan(0);
    cancelScrollMotion(); expect(vi.getTimerCount()).toBe(0);
  });
  it('settles the selected target before navigation captures its return position', () => {
    scrollToPosition(owner, 'top', 240, true); advance(32);
    cancelScrollMotion(undefined, undefined, true);
    expect(owner.scrollTop).toBe(240); expect(vi.getTimerCount()).toBe(0);
  });
  it('finishing cannot overwrite a newer external scroll position', () => {
    scrollToPosition(owner, 'top', 240, true); advance(32); owner.scrollTop = 600;
    cancelScrollMotion(undefined, undefined, true); expect(owner.scrollTop).toBe(600);
  });
});
