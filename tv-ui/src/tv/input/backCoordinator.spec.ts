import { describe, expect, it, vi } from 'vitest';
import { BackLayerRegistry, dispatchBack, type BackLayer } from './backCoordinator';

describe('dispatchBack', () => {
  it('prefers a page-owned handler over the shell fallback until the layer is released', () => {
    const owned = vi.fn(); const fallback = vi.fn();
    const registry = new BackLayerRegistry(() => undefined);
    const release = registry.register({ kind: 'page', close: owned });
    registry.dispatch([{ kind: 'page', close: fallback }]);
    expect(owned).toHaveBeenCalledOnce(); expect(fallback).not.toHaveBeenCalled();
    release(); registry.dispatch([{ kind: 'page', close: fallback }]);
    expect(fallback).toHaveBeenCalledOnce();
  });

  it('closes only the highest-priority active layer', () => {
    const closeIme = vi.fn();
    const closeDialog = vi.fn();
    const closePage = vi.fn();
    const layers: BackLayer[] = [
      { kind: 'page', close: closePage },
      { kind: 'dialog', close: closeDialog },
      { kind: 'ime', close: closeIme },
    ];

    expect(dispatchBack(layers)).toBe('ime');
    expect(closeIme).toHaveBeenCalledOnce();
    expect(closeDialog).not.toHaveBeenCalled();
    expect(closePage).not.toHaveBeenCalled();
  });

  it('uses route navigation before the home exit action', () => {
    const closePage = vi.fn();
    const requestExit = vi.fn();

    expect(
      dispatchBack([
        { kind: 'home-exit', close: requestExit },
        { kind: 'page', close: closePage },
      ]),
    ).toBe('page');
    expect(closePage).toHaveBeenCalledOnce();
    expect(requestExit).not.toHaveBeenCalled();
  });

  it('returns home-exit when no closable layer is registered', () => {
    expect(dispatchBack([])).toBe('home-exit');
  });

  it('registers overlays by priority and restores captured focus on release', () => {
    const restore = vi.fn();
    const closeDrawer = vi.fn();
    const closeIme = vi.fn();
    const registry = new BackLayerRegistry(restore);
    registry.register({ kind: 'drawer', close: closeDrawer }, 'open-library');
    const releaseIme = registry.register({ kind: 'ime', close: closeIme }, 'search-field');

    expect(registry.dispatch()).toBe('ime');
    expect(closeIme).toHaveBeenCalledOnce();
    releaseIme();
    expect(restore).toHaveBeenCalledWith('search-field');
    expect(registry.dispatch()).toBe('drawer');
  });

  it('closes the most recently registered layer when kinds have equal priority', () => {
    const closeOlderDialog = vi.fn();
    const closeTopDialog = vi.fn();

    expect(
      dispatchBack([
        { kind: 'dialog', close: closeOlderDialog },
        { kind: 'dialog', close: closeTopDialog },
      ]),
    ).toBe('dialog');
    expect(closeTopDialog).toHaveBeenCalledOnce();
    expect(closeOlderDialog).not.toHaveBeenCalled();
  });
});
