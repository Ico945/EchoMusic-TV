import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { installNativeClientForTest, type NativeClientLike } from '../native/client';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import { createTvRouter } from '../router';
import ShareDialog from './ShareDialog.vue';
import { tvFocusRegistry } from '../focus/directive';

function installShareClient(createResult: unknown): NativeClientLike {
  const client: NativeClientLike = {
    request: vi.fn((method: string) => {
      if (method === 'share.create') return Promise.resolve(createResult);
      return Promise.resolve({});
    }) as NativeClientLike['request'],
    on: () => () => undefined,
  };
  installNativeClientForTest(client);
  return client;
}

function mountDialog(props: { kind: string; id: string; title?: string; sourceFocusId?: string }) {
  window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createTvRouter();
  return mount(ShareDialog, {
    props,
    global: {
      plugins: [pinia, router],
      directives: { tvFocus: {} },
      provide: { [BACK_LAYER_REGISTRY_KEY as symbol]: new BackLayerRegistry(() => undefined) },
    },
  });
}

describe('ShareDialog', () => {
  it('hides the copy button when clipboard is unavailable', async () => {
    installShareClient({ link: 'https://example.com/share/1', qrImageDataUrl: 'data:image/png;base64,AAAA', clipboardAvailable: false });
    const wrapper = mountDialog({ kind: 'song', id: 'tv_1', title: '测试歌曲' });
    await flushPromises();

    expect(wrapper.find('[data-action="copy-share-link"]').exists()).toBe(false);
  });

  it('renders the real QR data url as an image', async () => {
    const qr = 'data:image/png;base64,QRDATA';
    installShareClient({ link: 'https://example.com/share/1', qrImageDataUrl: qr, clipboardAvailable: false });
    const wrapper = mountDialog({ kind: 'song', id: 'tv_1', title: '测试歌曲' });
    await flushPromises();

    expect(wrapper.get('img').attributes('src')).toBe(qr);
  });

  it('focuses the close action only after the asynchronous QR is rendered', async () => {
    let resolve!: (value: unknown) => void;
    installShareClient(new Promise((done) => { resolve = done; }));
    const restore = vi.spyOn(tvFocusRegistry, 'restore');
    const wrapper = mountDialog({ kind: 'song', id: 'tv_1', title: '测试歌曲' });

    expect(restore).not.toHaveBeenCalledWith('share-close');
    resolve({ link: 'https://example.com/share/1', qrImageDataUrl: 'data:image/png;base64,QR', clipboardAvailable: false });
    await flushPromises();

    expect(wrapper.attributes('data-share-dialog')).toBeDefined();
    expect(restore).toHaveBeenCalledWith('share-close');
    expect(wrapper.get('[data-focus-id="share-close"]').attributes('data-focus-right')).toBe('share-close');
    restore.mockRestore();
  });
});
