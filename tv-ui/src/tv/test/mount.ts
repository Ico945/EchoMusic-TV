import { createTestingPinia } from '@pinia/testing';
import type { MountingOptions } from '@vue/test-utils';
import { vi } from 'vitest';
import { createTvRouter } from '@tv/router';
import { installNativeClientForTest, type NativeClientLike } from '@tv/native/client';

export type TvTestSeed = {
  authState?: 'loggedOut' | 'qrPending' | 'scanned' | 'loggedIn' | 'expired';
  microphone?: 'available' | 'permission-required' | 'unavailable';
};

export const nativeClient: NativeClientLike = {
  request: vi.fn(),
  on: vi.fn(() => () => undefined),
};

installNativeClientForTest(nativeClient);

export function testingPinia(initialState: Record<string, object> = {}) {
  return createTestingPinia({ createSpy: vi.fn, stubActions: false, initialState });
}

export function configuredTvMount(seed: TvTestSeed = {}): MountingOptions<any> {
  return {
    global: {
      plugins: [
        createTvRouter(),
        testingPinia({
          'tv-auth': { state: seed.authState ?? 'loggedOut' },
          'tv-capabilities': { microphone: seed.microphone ?? 'available' },
        }),
      ],
    },
  };
}
