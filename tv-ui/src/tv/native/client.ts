import { BRIDGE_VERSION, type BridgeError } from './protocol';
import { createNativeTransport, type NativeTransport } from './transport';
import {
  BridgeValidationError,
  decodeBridgeMessage,
  isUnsupportedEvent,
  requestIdFrom,
  validateInboundEnvelope,
  validateRequestEnvelope,
} from './validators';

const DEFAULT_TIMEOUT_MS = 10_000;

type PendingRequest = {
  resolve: (payload: unknown) => void;
  reject: (error: BridgeError) => void;
  timer: ReturnType<typeof setTimeout>;
};

type EventHandler = (payload: unknown) => void;

function createRequestId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `tv-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function bridgeError(code: string, message: string, requestId?: string): BridgeError {
  return { code, message, retryable: false, scope: 'bridge', ...(requestId === undefined ? {} : { requestId }) };
}

function asBridgeError(error: unknown, requestId?: string): BridgeError {
  if (error instanceof BridgeValidationError) return bridgeError(error.code, error.message, requestId);
  return bridgeError('BRIDGE_PROTOCOL_INVALID', 'Bridge message is invalid', requestId);
}

export class NativeClient {
  private readonly pending = new Map<string, PendingRequest>();
  private readonly eventHandlers = new Map<string, Set<EventHandler>>();
  private readonly unsubscribeTransport: () => void;
  private disposed = false;

  constructor(
    private readonly transport: NativeTransport,
    private readonly nextRequestId: () => string = createRequestId,
  ) {
    this.unsubscribeTransport = this.transport.subscribe((message) => this.receive(message));
  }

  request<T>(method: string, payload: unknown, timeoutMs = DEFAULT_TIMEOUT_MS): Promise<T> {
    const id = this.nextRequestId();
    if (this.disposed) return Promise.reject(bridgeError('BRIDGE_DISPOSED', 'Native client is disposed', id));
    if (this.pending.has(id)) {
      return Promise.reject(bridgeError('BRIDGE_DUPLICATE_REQUEST_ID', 'Bridge request id is already pending', id));
    }

    let envelope: string;
    try {
      envelope = JSON.stringify(validateRequestEnvelope({ version: BRIDGE_VERSION, type: 'request', id, method, payload }));
    } catch (error) {
      return Promise.reject(asBridgeError(error, id));
    }

    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.settle(id, false, bridgeError('BRIDGE_TIMEOUT', 'Bridge request timed out', id));
      }, timeoutMs);
      this.pending.set(id, { resolve: (value) => resolve(value as T), reject, timer });
      try {
        this.transport.send(envelope);
      } catch {
        this.settle(id, false, bridgeError('BRIDGE_TRANSPORT_FAILED', 'Bridge transport failed', id));
      }
    });
  }

  on(event: string, handler: EventHandler): () => void {
    if (this.disposed) return () => undefined;
    const handlers = this.eventHandlers.get(event) ?? new Set<EventHandler>();
    handlers.add(handler);
    this.eventHandlers.set(event, handlers);
    return () => {
      handlers.delete(handler);
      if (handlers.size === 0) this.eventHandlers.delete(event);
    };
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.unsubscribeTransport();
    for (const id of this.pending.keys()) {
      this.settle(id, false, bridgeError('BRIDGE_DISPOSED', 'Native client is disposed', id));
    }
    this.eventHandlers.clear();
  }

  private receive(raw: string): void {
    let value: unknown;
    try {
      value = decodeBridgeMessage(raw);
    } catch {
      return;
    }

    if (isUnsupportedEvent(value)) return;

    try {
      const envelope = validateInboundEnvelope(value);
      if (envelope.type === 'event') {
        this.eventHandlers.get(envelope.event)?.forEach((handler) => handler(envelope.payload));
      } else if (envelope.ok) {
        this.settle(envelope.id, true, envelope.payload);
      } else {
        this.settle(envelope.id, false, { ...envelope.error!, requestId: envelope.id });
      }
    } catch (error) {
      const id = requestIdFrom(value);
      if (id) this.settle(id, false, asBridgeError(error, id));
    }
  }

  private settle(id: string, ok: boolean, value: unknown): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    this.pending.delete(id);
    clearTimeout(pending.timer);
    if (ok) pending.resolve(value);
    else pending.reject(value as BridgeError);
  }
}

export type NativeClientLike = Pick<NativeClient, 'request' | 'on'>;

export let nativeClient: NativeClientLike = new NativeClient(createNativeTransport());

export function installNativeClientForTest(client: NativeClientLike): void {
  if (import.meta.env.MODE !== 'test') throw new Error('Test client injection is disabled');
  nativeClient = client;
}
