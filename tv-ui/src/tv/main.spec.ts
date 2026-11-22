import { getActivePinia } from 'pinia';
import type { App } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';

type VueMountTarget = HTMLElement & { __vue_app__?: App };

afterEach(() => {
  document.querySelector<VueMountTarget>('#app')?.__vue_app__?.unmount();
  document.body.replaceChildren();
  vi.resetModules();
});

describe('TV entry', () => {
  it('mounts the 1920×1080 canvas and installed Pinia through the real entry', async () => {
    document.body.innerHTML = '<div id="app"></div>';

    await import('./main');

    const target = document.querySelector<VueMountTarget>('#app');
    const canvas = document.querySelector<HTMLElement>('.tv-canvas');
    const safeArea = document.querySelector<HTMLElement>('[data-tv-safe-area]');

    expect(target?.__vue_app__).toBeDefined();
    expect(target?.__vue_app__?.config.globalProperties.$pinia).toBe(getActivePinia());
    expect(canvas).not.toBeNull();
    expect(getComputedStyle(canvas!).width).toBe('1920px');
    expect(getComputedStyle(canvas!).height).toBe('1080px');
    expect(getComputedStyle(canvas!).gridTemplateColumns).toBe('5% 90% 5%');
    expect(getComputedStyle(canvas!).gridTemplateRows).toBe('5% 90% 5%');
    expect(getComputedStyle(safeArea!).gridColumn).toBe('2');
    expect(getComputedStyle(safeArea!).gridRow).toBe('2');
  // This includes Vite's cold transformation of every routed SFC on WSL/NTFS,
  // not just app mounting. Device launch latency is checked by the Android
  // harness separately; retain every mount/style assertion under this budget.
  }, 45_000);
});
