import { afterEach, describe, expect, it, vi } from 'vitest';
import { contentScrollOwner, scrollWithin } from './scrollVisibility';

afterEach(() => document.body.replaceChildren());
function fixture(y: number, height = 40) {
  const canvas = document.createElement('main');
  const surface = document.createElement('section');
  const target = document.createElement('button');
  canvas.append(surface); surface.append(target); document.body.append(canvas);
  surface.getBoundingClientRect = () => new DOMRect(100, 20, 400, 400);
  Object.defineProperty(surface, 'offsetHeight', { value: 800 });
  target.getBoundingClientRect = () => new DOMRect(110, y, 100, height);
  surface.scrollBy = vi.fn(); canvas.scrollBy = vi.fn(); target.scrollIntoView = vi.fn();
  return { canvas, surface, target };
}
describe('owned content scrolling', () => {
  it('does nothing for an already visible item', () => {
    const { surface, target } = fixture(60); scrollWithin(target, surface);
    expect(surface.scrollBy).not.toHaveBeenCalled(); expect(target.scrollIntoView).not.toHaveBeenCalled();
  });
  it('scrolls the content surface down in unscaled CSS pixels only', () => {
    const { canvas, surface, target } = fixture(430); scrollWithin(target, surface);
    expect(surface.scrollBy).toHaveBeenCalledWith({ top: 116, left: 0, behavior: 'auto' });
    expect(canvas.scrollBy).not.toHaveBeenCalled(); expect(target.scrollIntoView).not.toHaveBeenCalled();
  });
  it('scrolls up without shifting the canvas horizontally', () => {
    const { surface, target } = fixture(-12); scrollWithin(target, surface);
    expect(surface.scrollBy).toHaveBeenCalledWith({ top: -80, left: 0, behavior: 'auto' });
  });
  it('honors floating player occlusion and explicit motion preference', () => {
    const { surface, target } = fixture(300); scrollWithin(target, surface, { bottomLimit: 290, behavior: 'smooth' });
    expect(surface.scrollBy).toHaveBeenCalledWith({ top: 100, left: 0, behavior: 'smooth' });
  });
  it('centers lyrics within their own scroll area', () => {
    const { canvas, surface, target } = fixture(300); scrollWithin(target, surface, { block: 'center' });
    expect(surface.scrollBy).toHaveBeenCalledWith({ top: 200, left: 0, behavior: 'auto' });
    expect(canvas.scrollBy).not.toHaveBeenCalled();
  });
  it('does not oscillate a target taller than the visible area', () => {
    const { surface, target } = fixture(0, 500); scrollWithin(target, surface);
    expect(surface.scrollBy).not.toHaveBeenCalled();
  });
  it('falls back to scrollTop on older scroll implementations', () => {
    const { surface, target } = fixture(430); surface.scrollBy = undefined as never;
    scrollWithin(target, surface); expect(surface.scrollTop).toBe(116);
  });
  it('chooses a nested drawer without escaping to a scrollable outer canvas', () => {
    const { canvas, surface, target } = fixture(430); canvas.style.overflowY = 'auto';
    expect(contentScrollOwner(target, surface)).toBe(surface);
    const drawer = document.createElement('div'); drawer.style.overflowY = 'auto';
    Object.defineProperty(drawer, 'scrollHeight', { value: 800 });
    Object.defineProperty(drawer, 'clientHeight', { value: 400 });
    surface.append(drawer); drawer.append(target);
    expect(contentScrollOwner(target, surface)).toBe(drawer);
  });
  it('does not let a horizontal TV shelf capture vertical page reveal', () => {
    const { surface, target } = fixture(430);
    const shelf = document.createElement('div'); shelf.style.overflowY = 'auto';
    Object.defineProperty(shelf, 'scrollHeight', { value: 350 });
    Object.defineProperty(shelf, 'clientHeight', { value: 350 });
    surface.append(shelf); shelf.append(target);
    expect(contentScrollOwner(target, surface)).toBe(surface);
  });
});
