import { afterEach, describe, expect, it } from 'vitest';
import { FocusRegistry } from './registry';

const mounted: HTMLElement[] = [];
afterEach(() => { mounted.splice(0).forEach(el => el.remove()); });
function setup() {
  const registry = new FocusRegistry();
  const content = document.createElement('section'); document.body.append(content); mounted.push(content);
  function add(id: string, y: number, parent: HTMLElement = content) {
    const el = document.createElement('button'); el.dataset.focusId = id;
    el.getBoundingClientRect = () => new DOMRect(100, y, 200, 70);
    parent.append(el); mounted.push(el); registry.register(el); return el;
  }
  return { registry, content, add };
}
describe('TV focus zones', () => {
  it('does not follow a legacy sideways nav override outside the content zone', () => {
    const { registry, content, add } = setup();
    const nav = add('nav-home', 0, document.body); const song = add('song', 150);
    song.dataset.focusLeft = 'nav-home'; song.focus();
    expect(registry.move('left', content)).toBe(false);
    expect(document.activeElement).toBe(song);
    expect(registry.restore('nav-home')).toBe(true); expect(document.activeElement).toBe(nav);
  });
  it('resolves page neighbors before yielding its bottom boundary to the dock', () => {
    const { registry, content, add } = setup();
    const first = add('first', 100); const last = add('last', 200); add('mini-open', 400, document.body);
    first.focus(); expect(registry.move('down', content)).toBe(true); expect(document.activeElement).toBe(last);
    last.dataset.focusDown = 'last'; expect(registry.move('down', content)).toBe(false);
    expect(document.activeElement).toBe(last);
  });
  it('preserves self links for unscoped dialogs and rejects inert candidates', () => {
    const { registry, content, add } = setup();
    const first = add('first', 100); const last = add('last', 200);
    first.dataset.focusDown = 'first'; first.focus();
    expect(registry.move('down')).toBe(true); expect(document.activeElement).toBe(first);
    last.setAttribute('inert', ''); expect(registry.move('down', content)).toBe(false);
  });
});
