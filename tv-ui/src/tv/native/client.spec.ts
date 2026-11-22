import fixtures from '../../../contracts/tv-bridge/v1-fixtures.json';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NativeClient } from './client';
import { MockTransport } from './mockTransport';
import { createNativeTransport } from './transport';

afterEach(() => {
  vi.useRealTimers();
  delete window.EchoTvNative;
});

describe('NativeClient', () => {
  it('correlates a response by request id (catches response delivery to the wrong caller)', async () => {
    const transport = new MockTransport();
    const client = new NativeClient(transport, () => 'request-1');

    const pending = client.request('app.getSnapshot', {});
    transport.receive(fixtures.validResponse);

    await expect(pending).resolves.toEqual({ route: 'home' });
    expect(JSON.parse(transport.sent[0])).toEqual(fixtures.validRequest);
  });

  it('rejects a request after its deadline (catches a pending request that never settles)', async () => {
    vi.useFakeTimers();
    const client = new NativeClient(new MockTransport(), () => 'request-2');
    const pending = client.request('content.home', {}, 1000);
    const rejection = expect(pending).rejects.toMatchObject({ code: 'BRIDGE_TIMEOUT', requestId: 'request-2' });

    await vi.advanceTimersByTimeAsync(1001);

    await rejection;
  });

  it('keeps the first request as the response owner when a generated id is duplicated (catches pending Map replacement)', async () => {
    vi.useFakeTimers();
    const transport = new MockTransport();
    const client = new NativeClient(transport, () => 'duplicate-id');
    let firstValue: unknown;
    let firstError: unknown;
    let duplicateError: unknown;
    void client.request('content.home', {}).then(
      (value) => {
        firstValue = value;
      },
      (error) => {
        firstError = error;
      },
    );
    void client.request('content.home', {}).catch((error) => {
      duplicateError = error;
    });

    transport.receive({ version: 1, type: 'response', id: 'duplicate-id', ok: true, payload: { route: 'home' } });
    client.dispose();
    await Promise.resolve();

    expect(firstValue).toEqual({ route: 'home' });
    expect(firstError).toBeUndefined();
    expect(duplicateError).toMatchObject({ code: 'BRIDGE_DUPLICATE_REQUEST_ID', requestId: 'duplicate-id' });
    expect(transport.sent).toHaveLength(1);
  });

  it('preserves the first request timeout when a duplicate uses a shorter deadline (catches timer replacement)', async () => {
    vi.useFakeTimers();
    const client = new NativeClient(new MockTransport(), () => 'duplicate-timeout');
    let firstError: unknown;
    let duplicateError: unknown;
    void client.request('content.home', {}, 100).catch((error) => {
      firstError = error;
    });
    void client.request('content.home', {}, 1).catch((error) => {
      duplicateError = error;
    });

    await vi.advanceTimersByTimeAsync(101);

    expect(duplicateError).toMatchObject({ code: 'BRIDGE_DUPLICATE_REQUEST_ID', requestId: 'duplicate-timeout' });
    expect(firstError).toMatchObject({ code: 'BRIDGE_TIMEOUT', requestId: 'duplicate-timeout' });
  });

  it('rejects the original request on dispose after a duplicate id attempt (catches disposal of the replacement)', async () => {
    const client = new NativeClient(new MockTransport(), () => 'duplicate-dispose');
    let firstError: unknown;
    let duplicateError: unknown;
    void client.request('content.home', {}).catch((error) => {
      firstError = error;
    });
    void client.request('content.home', {}).catch((error) => {
      duplicateError = error;
    });

    client.dispose();
    await Promise.resolve();

    expect(duplicateError).toMatchObject({ code: 'BRIDGE_DUPLICATE_REQUEST_ID', requestId: 'duplicate-dispose' });
    expect(firstError).toMatchObject({ code: 'BRIDGE_DISPOSED', requestId: 'duplicate-dispose' });
  });

  it('dispatches a validated Bridge v1 event and stops after unsubscribe (catches event listener leaks)', () => {
    const transport = new MockTransport();
    const client = new NativeClient(transport);
    const received: unknown[] = [];
    const unsubscribe = client.on('playback.changed', (payload) => received.push(payload));

    transport.receive(fixtures.validEvent);
    unsubscribe();
    transport.receive(fixtures.validEvent);

    expect(received).toEqual([{ state: 'playing' }]);
  });

  it('dispatches the shared exact Android app error contract', () => {
    const transport = new MockTransport();
    const client = new NativeClient(transport);
    const received: unknown[] = [];
    client.on('app.error', (payload) => received.push(payload));

    transport.receive(fixtures.validAppErrorEvent);

    expect(received).toEqual([fixtures.validAppErrorEvent.payload]);
    expect(Object.keys(received[0] as object).sort()).toEqual(['code', 'errorId', 'message', 'retryable']);
  });

  it('ignores events from unsupported Bridge versions (catches cross-version event dispatch)', () => {
    const transport = new MockTransport();
    const client = new NativeClient(transport);
    const received: unknown[] = [];
    client.on('playback.changed', (payload) => received.push(payload));

    transport.receive(fixtures.wrongVersionEvent);

    expect(received).toEqual([]);
  });

  it.each([
    ['an unsupported response version', fixtures.wrongVersionResponse, 'BRIDGE_VERSION_UNSUPPORTED'],
    ['a malformed response payload', fixtures.malformedPayloadResponse, 'BRIDGE_PROTOCOL_INVALID'],
    ['a response containing a sensitive field', fixtures.sensitiveFieldResponse, 'BRIDGE_SENSITIVE_FIELD'],
  ])('rejects %s (catches unsafe inbound data)', async (_description, response, code) => {
    const transport = new MockTransport();
    const client = new NativeClient(transport, () => 'request-3');
    const pending = client.request('content.home', {});

    transport.receive(response);

    await expect(pending).rejects.toMatchObject({ code, requestId: 'request-3' });
  });

  it('rejects an outgoing payload containing a sensitive field before it reaches the transport (catches secret leakage)', async () => {
    const transport = new MockTransport();
    const client = new NativeClient(transport, () => 'request-4');

    await expect(client.request('content.home', { accessToken: 'secret' })).rejects.toMatchObject({
      code: 'BRIDGE_SENSITIVE_FIELD',
      requestId: 'request-4',
    });
    expect(transport.sent).toEqual([]);
  });

  it.each(['url', 'streamUrl', 'hash', 'secret', 'source'])(
    'rejects inbound %s fields before event or response dispatch',
    async (field) => {
      const transport = new MockTransport();
      const client = new NativeClient(transport, () => `sensitive-${field}`);
      const pending = client.request('content.home', {});

      transport.receive({
        version: 1,
        type: 'response',
        id: `sensitive-${field}`,
        ok: true,
        payload: { [field]: 'redacted' },
      });

      await expect(pending).rejects.toMatchObject({ code: 'BRIDGE_SENSITIVE_FIELD' });
    },
  );

  it('rejects every outstanding request when disposed (catches callers left pending during teardown)', async () => {
    const transport = new MockTransport();
    const client = new NativeClient(transport, () => 'request-5');
    const pending = client.request('content.home', {});

    client.dispose();

    await expect(pending).rejects.toMatchObject({ code: 'BRIDGE_DISPOSED', requestId: 'request-5' });
    expect(transport.subscriberCount).toBe(0);
  });

  it('accepts a failed response only when its error requestId matches the envelope id (catches response error correlation loss)', async () => {
    const transport = new MockTransport();
    const client = new NativeClient(transport, () => 'request-6');
    const pending = client.request('content.home', {});

    transport.receive({
      version: 1,
      type: 'response',
      id: 'request-6',
      ok: false,
      error: { code: 'CONTENT_UNAVAILABLE', message: 'Unavailable', retryable: true, scope: 'content', requestId: 'request-6' },
    });

    await expect(pending).rejects.toMatchObject({ code: 'CONTENT_UNAVAILABLE', requestId: 'request-6' });
  });

  it('rejects a failed response whose error requestId differs from its envelope id (catches forged error correlation)', async () => {
    const transport = new MockTransport();
    const client = new NativeClient(transport, () => 'request-7');
    const pending = client.request('content.home', {});

    transport.receive({
      version: 1,
      type: 'response',
      id: 'request-7',
      ok: false,
      error: { code: 'CONTENT_UNAVAILABLE', message: 'Unavailable', retryable: true, scope: 'content', requestId: 'other-request' },
    });

    await expect(pending).rejects.toMatchObject({ code: 'BRIDGE_PROTOCOL_INVALID', requestId: 'request-7' });
  });
});

describe('createNativeTransport', () => {
  it('uses window.EchoTvNative on Android and forwards messages in both directions', () => {
    const posted: string[] = [];
    window.EchoTvNative = { postMessage: (message) => posted.push(message), onmessage: null };
    const transport = createNativeTransport();
    const received: string[] = [];
    const unsubscribe = transport.subscribe((message) => received.push(message));

    transport.send('request');
    window.EchoTvNative.onmessage?.({ data: 'response' });
    unsubscribe();

    expect(posted).toEqual(['request']);
    expect(received).toEqual(['response']);
    expect(window.EchoTvNative.onmessage).toBeNull();
  });

  it('keeps remaining Android subscribers active when subscriptions unsubscribe out of order (catches global onmessage cleanup)', () => {
    window.EchoTvNative = { postMessage: () => undefined, onmessage: null };
    const transport = createNativeTransport();
    const first: string[] = [];
    const second: string[] = [];
    const unsubscribeFirst = transport.subscribe((message) => first.push(message));
    const unsubscribeSecond = transport.subscribe((message) => second.push(message));

    window.EchoTvNative.onmessage?.({ data: 'before-first-unsubscribe' });
    unsubscribeFirst();
    window.EchoTvNative.onmessage?.({ data: 'after-first-unsubscribe' });
    unsubscribeSecond();

    expect(first).toEqual(['before-first-unsubscribe']);
    expect(second).toEqual(['before-first-unsubscribe', 'after-first-unsubscribe']);
    expect(window.EchoTvNative.onmessage).toBeNull();
  });

  it('falls back to MockTransport when the Android bridge is unavailable', () => {
    expect(createNativeTransport()).toBeInstanceOf(MockTransport);
  });

  it('upgrades a startup fallback when Android injects the production bridge before the first request', () => {
    const transport = createNativeTransport();
    const received: string[] = [];
    const unsubscribe = transport.subscribe((message) => received.push(message));
    const posted: string[] = [];

    window.EchoTvNative = { postMessage: (message) => posted.push(message), onmessage: null };
    transport.send('late-request');
    window.EchoTvNative.onmessage?.({ data: 'late-response' });
    unsubscribe();

    expect(posted).toEqual(['late-request']);
    expect(received).toEqual(['late-response']);
    expect(window.EchoTvNative.onmessage).toBeNull();
  });

  it('flushes a bounded startup request queued just before Android injects the production bridge', async () => {
    vi.useFakeTimers();
    const transport = createNativeTransport();
    const posted: string[] = [];

    transport.send('queued-request');
    window.EchoTvNative = { postMessage: (message) => posted.push(message), onmessage: null };
    await vi.advanceTimersByTimeAsync(25);

    expect(posted).toEqual(['queued-request']);
  });
});
