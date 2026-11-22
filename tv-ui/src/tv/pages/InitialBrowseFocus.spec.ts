import { createPinia } from 'pinia';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createTvRouter } from '../router';
import { vTvFocus, tvFocusRegistry } from '../focus/directive';
import { BackLayerRegistry, BACK_LAYER_REGISTRY_KEY } from '../input/backCoordinator';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import RankingPage from './RankingPage.vue';
import ExplorePage from './ExplorePage.vue';
import PrivateFmPage from './PrivateFmPage.vue';

let wrapper: VueWrapper | undefined;
const controls: HTMLButtonElement[] = [];
afterEach(() => {
  wrapper?.unmount(); wrapper = undefined;
  controls.splice(0).forEach(control => { tvFocusRegistry.unregister(control); control.remove(); });
  delete window.EchoTvNative;
  vi.restoreAllMocks();
});

function control(id: string) {
  const button = document.createElement('button');
  button.dataset.focusId = id;
  document.body.append(button);
  tvFocusRegistry.register(button);
  controls.push(button);
  return button;
}

async function start(page: 'ranking' | 'explore' | 'fm') {
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  let finish!: (payload: unknown) => void;
  installNativeClientForTest({
    request: vi.fn(() => new Promise(resolve => { finish = resolve; })) as NativeClientLike['request'],
    on: () => () => undefined,
  });
  const router = createTvRouter();
  await router.push(`/${page}`); await router.isReady();
  const nav = control(`nav-${page}`); nav.focus();
  const component = page === 'ranking' ? RankingPage : page === 'explore' ? ExplorePage : PrivateFmPage;
  wrapper = mount(component, {
    attachTo: document.body,
    global: {
      plugins: [createPinia(), router], directives: { tvFocus: vTvFocus },
      provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: new BackLayerRegistry(() => undefined) },
    },
  });
  return async () => {
    finish(page === 'fm'
      ? { items: [{ id: 'track1', title: 'Song', artist: 'Artist', album: '', artwork: '', durationMs: 180000 }] }
      : { newSongs: [], newAlbums: [], newPlaylists: [], artists: [], rankings: [{ id: 'rank1', title: 'Rank', artwork: '' }] });
    await flushPromises();
  };
}

describe.each(['ranking', 'explore', 'fm'] as const)('%s initial async focus', page => {
  it('enters loaded content when the page navigation entry still owns focus', async () => {
    const finish = await start(page); await finish();
    expect(wrapper!.element.contains(document.activeElement)).toBe(true);
    expect((document.activeElement as HTMLElement).dataset.focusId).toBeTruthy();
  });

  it.each(['nav-home', 'mini-toggle', 'login-cancel'])('preserves focus moved to %s while content is pending', async id => {
    const finish = await start(page);
    const selected = control(id); selected.focus();
    await finish();
    expect(document.activeElement).toBe(selected);
  });

  it('does not register late page focus after unmount', async () => {
    const finish = await start(page);
    wrapper!.unmount(); wrapper = undefined;
    const selected = control('nav-home'); selected.focus();
    const restore = vi.spyOn(tvFocusRegistry, 'restore');
    await finish();
    expect(restore).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(selected);
  });
});
