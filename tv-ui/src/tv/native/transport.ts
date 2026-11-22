import { MockTransport } from './mockTransport';

declare global {
  interface Window {
    EchoTvNative?: {
      postMessage(message: string): void;
      onmessage: ((event: { data: string }) => void) | null;
    };
  }
}

export interface NativeTransport {
  send(message: string): void;
  subscribe(handler: (message: string) => void): () => void;
}

export function isNativeBridgeAvailable(): boolean {
  return Boolean(window.EchoTvNative);
}

class AndroidWebMessageTransport implements NativeTransport {
  private readonly handlers = new Set<(message: string) => void>();
  private readonly dispatch = (event: { data: string }) => {
    for (const handler of this.handlers) handler(event.data);
  };

  constructor(private readonly bridge: NonNullable<Window['EchoTvNative']>) {}

  send(message: string): void {
    this.bridge.postMessage(message);
  }

  subscribe(handler: (message: string) => void): () => void {
    if (this.handlers.size === 0) this.bridge.onmessage = this.dispatch;
    this.handlers.add(handler);
    return () => {
      this.handlers.delete(handler);
      if (this.handlers.size === 0 && this.bridge.onmessage === this.dispatch) this.bridge.onmessage = null;
    };
  }
}

class LateBindingAndroidTransport extends MockTransport {
  private static readonly RETRY_INTERVAL_MS = 25;
  private static readonly MAX_RETRIES = 80;
  private static readonly MAX_QUEUED_MESSAGES = 32;
  private readonly liveSubscriptions = new Set<{
    handler: (message: string) => void;
    unsubscribeAndroid?: () => void;
  }>();
  private androidBridge?: NonNullable<Window['EchoTvNative']>;
  private androidTransport?: AndroidWebMessageTransport;
  private readonly queuedMessages: string[] = [];
  private retryTimer?: ReturnType<typeof setTimeout>;
  private retries = 0;

  override send(message: string): void {
    this.bindAndroidBridge();
    if (this.androidTransport) {
      this.androidTransport.send(message);
      return;
    }
    if (this.queuedMessages.length >= LateBindingAndroidTransport.MAX_QUEUED_MESSAGES) this.queuedMessages.shift();
    this.queuedMessages.push(message);
    this.scheduleBridgeRetry();
  }

  override subscribe(handler: (message: string) => void): () => void {
    const unsubscribeMock = super.subscribe(handler);
    const subscription = { handler } as {
      handler: (message: string) => void;
      unsubscribeAndroid?: () => void;
    };
    this.liveSubscriptions.add(subscription);
    this.bindAndroidBridge();
    if (this.androidTransport && !subscription.unsubscribeAndroid) {
      subscription.unsubscribeAndroid = this.androidTransport.subscribe(handler);
    }
    return () => {
      unsubscribeMock();
      subscription.unsubscribeAndroid?.();
      this.liveSubscriptions.delete(subscription);
    };
  }

  private bindAndroidBridge(): void {
    const bridge = window.EchoTvNative;
    if (!bridge || bridge === this.androidBridge) return;
    for (const subscription of this.liveSubscriptions) subscription.unsubscribeAndroid?.();
    this.androidBridge = bridge;
    this.androidTransport = new AndroidWebMessageTransport(bridge);
    for (const subscription of this.liveSubscriptions) {
      subscription.unsubscribeAndroid = this.androidTransport.subscribe(subscription.handler);
    }
    const queued = this.queuedMessages.splice(0);
    for (const message of queued) this.androidTransport.send(message);
    if (this.retryTimer !== undefined) clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
    this.retries = 0;
  }

  private scheduleBridgeRetry(): void {
    if (this.retryTimer !== undefined) return;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      this.bindAndroidBridge();
      if (this.androidTransport || this.queuedMessages.length === 0) return;
      this.retries += 1;
      if (this.retries >= LateBindingAndroidTransport.MAX_RETRIES) {
        const queued = this.queuedMessages.splice(0);
        for (const message of queued) super.send(message);
        this.retries = 0;
        return;
      }
      this.scheduleBridgeRetry();
    }, LateBindingAndroidTransport.RETRY_INTERVAL_MS);
  }
}

export function createNativeTransport(): NativeTransport {
  if (isNativeBridgeAvailable()) return new AndroidWebMessageTransport(window.EchoTvNative!);
  return new LateBindingAndroidTransport();
}
