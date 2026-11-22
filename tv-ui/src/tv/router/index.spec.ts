import { afterEach, describe, expect, it } from 'vitest';
import { createTvAppRouter } from './index';

describe('production TV router history', () => {
  afterEach(() => window.history.replaceState(null, '', '/'));

  it('keeps the asset document path and exposes the MV route in the observed hash', async () => {
    window.history.replaceState(null, '', '/assets/tv-ui/tv.html');
    const router = createTvAppRouter();

    await router.push('/mv/mv%3A42?quality=hd');
    await router.isReady();

    expect(window.location.pathname).toBe('/assets/tv-ui/tv.html');
    expect(window.location.hash).toBe('#/mv/mv%3A42?quality=hd');
  });
});
