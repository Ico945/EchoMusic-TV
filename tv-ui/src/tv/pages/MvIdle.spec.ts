import { createPinia } from 'pinia';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BackLayerRegistry, BACK_LAYER_REGISTRY_KEY } from '../input/backCoordinator';
import { vTvFocus } from '../focus/directive';
import { installNativeClientForTest } from '../native/client';
import { createTvRouter } from '../router';
import MvDetailPage from './MvDetailPage.vue';

let wrapper: VueWrapper | undefined;
let playing = true, busy = false, error = false;
const request = vi.fn();
beforeEach(() => {
  vi.useFakeTimers(); playing = true; busy = false; error = false;
  request.mockReset().mockImplementation((method: string) => Promise.resolve(method === 'video.snapshot'
    ? { durationMs: 180000, positionMs: Date.now() % 180000, playing, open: true, operation: busy ? 'quality' : null, quality: 'hd', errorCode: error ? 'MV_PLAYBACK_FAILED' : null }
    : { accepted: true, quality: 'hd' }));
  installNativeClientForTest({ request, on: () => () => undefined });
});
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.useRealTimers(); });
async function start() {
  const router = createTvRouter(); await router.push('/mv/mv:42'); await router.isReady();
  wrapper = mount(MvDetailPage, { attachTo: document.body, global: {
    plugins: [createPinia(), router], directives: { tvFocus: vTvFocus },
    provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: new BackLayerRegistry(() => undefined) },
  } });
  await flushPromises();
}
function key(key: string, type = 'keydown', repeat = false) {
  const event = new KeyboardEvent(type, { key, repeat, bubbles: true, cancelable: true });
  document.activeElement!.dispatchEvent(event); return event;
}
const hidden = () => wrapper!.attributes('data-mv-idle') === 'true';

describe('MV remote idle chrome', () => {
  it('hides after four seconds despite native position polling and retains root focus', async () => {
    await start();
    await vi.advanceTimersByTimeAsync(3900); expect(hidden()).toBe(false);
    await vi.advanceTimersByTimeAsync(200); expect(hidden()).toBe(true);
    expect(document.activeElement).toBe(wrapper!.element);
    expect(wrapper!.get('.mv-control-layer').attributes('inert')).toBeDefined();
    await vi.advanceTimersByTimeAsync(2000); expect(hidden()).toBe(true);
  });
  it.each(['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter'])('%s only wakes hidden controls and restores the previous control', async name => {
    await start();
    wrapper!.get<HTMLElement>('[data-focus-id=mv-toggle]').element.focus();
    await vi.advanceTimersByTimeAsync(4100); request.mockClear();
    expect(key(name).defaultPrevented).toBe(true); await flushPromises();
    expect(hidden()).toBe(false);
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('mv-toggle');
    expect(request.mock.calls.some(([method]) => method === 'video.toggle' || method === 'video.seek')).toBe(false);
    if (name === 'Enter') {
      expect(key(name, 'keydown', true).defaultPrevented).toBe(true);
      expect(key(name, 'keyup').defaultPrevented).toBe(true);
      expect(key(name).defaultPrevented).toBe(false);
    }
  });
  it.each(['pause', 'busy', 'error'])('keeps controls visible during %s and restarts after recovery', async state => {
    await start(); await vi.advanceTimersByTimeAsync(4100); expect(hidden()).toBe(true);
    playing = state !== 'pause'; busy = state === 'busy'; error = state === 'error';
    await vi.advanceTimersByTimeAsync(600); expect(hidden()).toBe(false);
    await vi.advanceTimersByTimeAsync(4500); expect(hidden()).toBe(false);
    playing = true; busy = false; error = false;
    await vi.advanceTimersByTimeAsync(600); expect(hidden()).toBe(false);
    await vi.advanceTimersByTimeAsync(4100); expect(hidden()).toBe(true);
  });
  it('accepts a new confirmation when Android omits the wake keyup after focus changes', async () => {
    await start(); await vi.advanceTimersByTimeAsync(4100);
    expect(key('Enter').defaultPrevented).toBe(true); await flushPromises();
    expect(key('Enter', 'keydown', true).defaultPrevented).toBe(true);
    // TvRemoteWebView does not deliver the old keyup to a different recipient.
    expect(key('Enter').defaultPrevented).toBe(false);
  });
  it('media commands reveal and propagate once; Back is never swallowed', async () => {
    await start(); await vi.advanceTimersByTimeAsync(4100);
    expect(key('MediaPlayPause').defaultPrevented).toBe(false); await flushPromises(); expect(hidden()).toBe(false);
    expect(key('GoBack').defaultPrevented).toBe(false);
    wrapper!.unmount(); wrapper = undefined;
    await vi.advanceTimersByTimeAsync(5000);
    expect(document.documentElement.classList.contains('tv-mv-active')).toBe(false);
  });
});
