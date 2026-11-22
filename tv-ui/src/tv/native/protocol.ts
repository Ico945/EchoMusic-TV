export const BRIDGE_VERSION = 1 as const;

export type BridgeError = {
  code: string;
  message: string;
  retryable: boolean;
  scope: string;
  requestId?: string;
};

export type RequestEnvelope = {
  version: typeof BRIDGE_VERSION;
  type: 'request';
  id: string;
  method: string;
  payload: unknown;
};

export type ResponseEnvelope = {
  version: typeof BRIDGE_VERSION;
  type: 'response';
  id: string;
  ok: boolean;
  payload?: unknown;
  error?: BridgeError;
};

export type EventEnvelope = {
  version: typeof BRIDGE_VERSION;
  type: 'event';
  event: string;
  payload: unknown;
};

export type InboundEnvelope = ResponseEnvelope | EventEnvelope;
