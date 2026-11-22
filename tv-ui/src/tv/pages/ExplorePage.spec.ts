import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createTvRouter } from '../router';
import { vTvFocus, tvFocusRegistry } from '../focus/directive';
import { installNativeClientForTest } from '../native/client';
import { useTvBrowseStore } from '../stores/browse';
import ExplorePage from './ExplorePage.vue';

const empty = { newSongs: [], newAlbums: [], newPlaylists: [], artists: [], rankings: [] };
let wrapper: VueWrapper | undefined;
let nav: HTMLButtonElement;
afterEach(() => {
  wrapper?.unmount(); wrapper = undefined;
  tvFocusRegistry.unregister(nav); nav?.remove();
  delete window.EchoTvNative;
});

async function setup(id: 'newSongs' | 'rankings' = 'newSongs') {
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  const pinia = createPinia(); setActivePinia(pinia);
  const store = useTvBrowseStore();
  const request = vi.fn().mockResolvedValue({ ...empty, failedSections: [id] });
  installNativeClientForTest({ request, on: () => () => undefined });
  await store.loadExplore();
  nav = document.createElement('button'); nav.dataset.focusId = 'nav-explore';
  document.body.append(nav); tvFocusRegistry.register(nav);
  const router = createTvRouter(); await router.push('/explore'); await router.isReady();
  wrapper = mount(ExplorePage, { attachTo: document.body, global: { plugins: [pinia, router], directives: { tvFocus: vTvFocus } } });
  await flushPromises();
  const button = wrapper.get<HTMLButtonElement>(`[data-focus-id="explore-${id}-retry"]`);
  button.element.focus();
  let resolve!: (value: unknown) => void;
  request.mockImplementation(() => new Promise<unknown>(done => { resolve = done; }));
  request.mockClear();
  await button.trigger('click');
  return { button, request, resolve: (value: unknown) => resolve(value) };
}

describe('Explore retry focus with real focus directive', () => {
  it('retains the focused button while loading and allows Left to navigation', async () => {
    const { button, resolve } = await setup();
    expect(button.element.isConnected).toBe(true);
    expect(document.activeElement).toBe(button.element);
    expect(button.attributes('aria-busy')).toBe('true');
    expect(tvFocusRegistry.move('left')).toBe(true);
    expect(document.activeElement).toBe(nav);
    resolve(empty); await flushPromises();
    expect(document.activeElement).toBe(nav);
  });
  it('retains the same retry and focus after another failure', async () => {
    const { button, resolve } = await setup();
    resolve({ ...empty, failedSections: ['newSongs'] }); await flushPromises();
    expect(document.activeElement).toBe(button.element);
    expect(button.text()).toContain('加载失败');
  });
  it('ignores repeated confirms until the active retry finishes', async () => {
    const { button, request, resolve } = await setup();
    await button.trigger('click');
    expect(request).toHaveBeenCalledTimes(1);
    resolve(empty); await flushPromises();
  });
  it('moves to the first recovered card', async () => {
    const { resolve } = await setup();
    resolve({ ...empty, newSongs: [{ id: 'song1', title: 'Fixture', kind: 'song', subtitle: '', artwork: '' }] });
    await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('explore-newSongs-0');
  });
  it('moves to the first recovered ranking', async () => {
    const { resolve } = await setup('rankings');
    resolve({ ...empty, rankings: [{ id: 'rank1', title: 'Fixture', artwork: '' }] }); await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('explore-rankings-rank1');
  });
  it('moves to the reload action when the successful response is genuinely empty', async () => {
    const { resolve } = await setup(); resolve(empty); await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('explore-first');
  });
  it('does not steal focus if the user leaves during loading', async () => {
    const { resolve } = await setup(); nav.focus();
    resolve({ ...empty, newSongs: [{ id: 'song1', title: 'Fixture', kind: 'song', subtitle: '', artwork: '' }] });
    await flushPromises(); expect(document.activeElement).toBe(nav);
  });
  it('does not restore focus after the page unmounts', async () => {
    const { resolve } = await setup(); wrapper!.unmount(); wrapper = undefined; nav.focus();
    const restore = vi.spyOn(tvFocusRegistry, 'restore');
    resolve(empty); await flushPromises();
    expect(restore).not.toHaveBeenCalled(); expect(document.activeElement).toBe(nav);
    restore.mockRestore();
  });
});
