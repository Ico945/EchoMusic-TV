import { createPinia } from 'pinia';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createTvRouter } from '../router';
import { vTvFocus, tvFocusRegistry } from '../focus/directive';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import DailyRecommendationsPage from './DailyRecommendationsPage.vue';
import SettingsPage from './SettingsPage.vue';
import RecognizePage from './RecognizePage.vue';

const pages = {
  'daily-recommendations': {
    component: DailyRecommendationsPage, nav: 'nav-home',
    payload: { items: [{ id: 'track1', title: 'Song', artist: 'Artist', album: '', artwork: '', durationMs: 180000 }] },
  },
  settings: {
    component: SettingsPage, nav: 'nav-more',
    payload: { settings: { reducedMotion: false, defaultQuality: '128', lyricFontScale: 100,
      lyricBackground: 'blur', cacheLimitMb: 1024, compatiblePluginsEnabled: true } },
  },
  recognize: {
    component: RecognizePage, nav: 'nav-more',
    payload: { microphone: 'unavailable', voiceInput: false, blur: false },
  },
};
type Page = keyof typeof pages;
let wrapper: VueWrapper | undefined;
const controls: HTMLButtonElement[] = [];
afterEach(() => {
  wrapper?.unmount(); wrapper = undefined;
  controls.splice(0).forEach(control => { tvFocusRegistry.unregister(control); control.remove(); });
  delete window.EchoTvNative;
  vi.restoreAllMocks();
});

function control(id: string) {
  const node = document.createElement('button'); node.dataset.focusId = id;
  document.body.append(node); tvFocusRegistry.register(node); controls.push(node);
  return node;
}

async function start(page: Page) {
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  let resolve!: (payload: unknown) => void;
  installNativeClientForTest({
    request: vi.fn(() => new Promise(done => { resolve = done; })) as NativeClientLike['request'],
    on: () => () => undefined,
  });
  const router = createTvRouter(); await router.push(`/${page}`); await router.isReady();
  const nav = control(pages[page].nav); nav.focus();
  wrapper = mount(pages[page].component, {
    attachTo: document.body,
    global: { plugins: [createPinia(), router], directives: { tvFocus: vTvFocus } },
  });
  return { nav, finish: async () => { resolve(pages[page].payload); await flushPromises(); } };
}

describe.each(Object.keys(pages) as Page[])('%s pending initial focus', page => {
  it('enters its controls after initial data is ready', async () => {
    const { finish } = await start(page); await finish();
    expect(wrapper!.element.contains(document.activeElement)).toBe(true);
    expect((document.activeElement as HTMLElement).dataset.focusId).toBeTruthy();
  });

  it.each(['nav-library', 'mini-toggle', 'login-cancel'])('preserves the new %s focus during loading', async id => {
    const { finish } = await start(page); const chosen = control(id); chosen.focus();
    await finish(); expect(document.activeElement).toBe(chosen);
  });

  it('keeps the navigation entry after a consumed remote key instead of entering content later', async () => {
    const { nav, finish } = await start(page);
    const consume = (event: Event) => event.stopPropagation();
    nav.addEventListener('keydown', consume);
    nav.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    await finish(); expect(document.activeElement).toBe(nav);
    nav.removeEventListener('keydown', consume);
  });

  it('cancels focus restoration when the page is left before the request finishes', async () => {
    const { finish } = await start(page); wrapper!.unmount(); wrapper = undefined;
    const chosen = control('nav-library'); chosen.focus();
    const restore = vi.spyOn(tvFocusRegistry, 'restore');
    await finish(); expect(restore).not.toHaveBeenCalled(); expect(document.activeElement).toBe(chosen);
  });
});
