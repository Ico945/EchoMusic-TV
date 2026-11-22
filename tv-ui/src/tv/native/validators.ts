import { BRIDGE_VERSION, type BridgeError, type EventEnvelope, type InboundEnvelope, type RequestEnvelope, type ResponseEnvelope } from './protocol';

const forbiddenStructureFields = new Set(['__proto__', 'constructor', 'prototype']);
const sensitiveFields = new Set([
  'token',
  'accesstoken',
  'refreshtoken',
  'deviceid',
  'deviceidentity',
  'signature',
  'qrkey',
  'qrsecret',
  'playbackurl',
  'url',
  'streamurl',
  'hash',
  'secret',
  'source',
]);

type JsonRecord = Record<string, unknown>;

export class BridgeValidationError extends Error {
  constructor(
    public readonly code: 'BRIDGE_PROTOCOL_INVALID' | 'BRIDGE_VERSION_UNSUPPORTED' | 'BRIDGE_SENSITIVE_FIELD',
    message: string,
  ) {
    super(message);
    this.name = 'BridgeValidationError';
  }
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requireRecord(value: unknown, label: string): JsonRecord {
  if (!isRecord(value)) throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', `${label} must be an object`);
  return value;
}

function requireString(value: unknown, label: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', `${label} must be a non-empty string`);
  }
  return value;
}

function requireExactFields(value: JsonRecord, allowed: string[], required: string[], label: string) {
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', `${label} contains an unknown field`);
  }
  for (const key of required) {
    if (!(key in value)) throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', `${label} is missing ${key}`);
  }
}

function validatePayload(value: unknown): void {
  if (Array.isArray(value)) {
    value.forEach(validatePayload);
    return;
  }
  if (!isRecord(value)) return;

  for (const [key, nested] of Object.entries(value)) {
    const normalized = key.replace(/[^a-z0-9]/gi, '').toLowerCase();
    if (forbiddenStructureFields.has(key)) {
      throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', 'Bridge payload contains an unsafe structural field');
    }
    if (sensitiveFields.has(normalized)) {
      throw new BridgeValidationError('BRIDGE_SENSITIVE_FIELD', 'Bridge payload contains a sensitive field');
    }
    validatePayload(nested);
  }
}

function validateBridgeError(value: unknown): BridgeError {
  const error = requireRecord(value, 'Bridge error');
  requireExactFields(error, ['code', 'message', 'retryable', 'scope', 'requestId'], ['code', 'message', 'retryable', 'scope'], 'Bridge error');
  if (typeof error.retryable !== 'boolean') {
    throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', 'Bridge error retryable must be boolean');
  }
  const requestId = error.requestId === undefined ? undefined : requireString(error.requestId, 'Bridge error requestId');
  return {
    code: requireString(error.code, 'Bridge error code'),
    message: requireString(error.message, 'Bridge error message'),
    retryable: error.retryable,
    scope: requireString(error.scope, 'Bridge error scope'),
    ...(requestId === undefined ? {} : { requestId }),
  };
}

function validateVersion(value: JsonRecord): void {
  if (value.version !== BRIDGE_VERSION) {
    throw new BridgeValidationError('BRIDGE_VERSION_UNSUPPORTED', 'Unsupported Bridge version');
  }
}

export function decodeBridgeMessage(raw: string): unknown {
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', 'Bridge message is not valid JSON');
  }
}

export function requestIdFrom(value: unknown): string | undefined {
  return isRecord(value) && typeof value.id === 'string' ? value.id : undefined;
}

export function isUnsupportedEvent(value: unknown): boolean {
  return isRecord(value) && value.type === 'event' && value.version !== BRIDGE_VERSION;
}

export function validateRequestEnvelope(value: unknown): RequestEnvelope {
  const request = requireRecord(value, 'Bridge request');
  requireExactFields(request, ['version', 'type', 'id', 'method', 'payload'], ['version', 'type', 'id', 'method', 'payload'], 'Bridge request');
  validateVersion(request);
  if (request.type !== 'request') throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', 'Bridge request type is invalid');
  validatePayload(request.payload);
  return {
    version: BRIDGE_VERSION,
    type: 'request',
    id: requireString(request.id, 'Bridge request id'),
    method: requireString(request.method, 'Bridge request method'),
    payload: request.payload,
  };
}

export function validateInboundEnvelope(value: unknown): InboundEnvelope {
  const envelope = requireRecord(value, 'Bridge envelope');
  validateVersion(envelope);

  if (envelope.type === 'event') {
    requireExactFields(envelope, ['version', 'type', 'event', 'payload'], ['version', 'type', 'event', 'payload'], 'Bridge event');
    validatePayload(envelope.payload);
    const event: EventEnvelope = { version: BRIDGE_VERSION, type: 'event', event: requireString(envelope.event, 'Bridge event name'), payload: envelope.payload };
    return event;
  }

  if (envelope.type !== 'response') throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', 'Bridge envelope type is invalid');
  requireExactFields(envelope, ['version', 'type', 'id', 'ok', 'payload', 'error'], ['version', 'type', 'id', 'ok'], 'Bridge response');
  if (typeof envelope.ok !== 'boolean') throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', 'Bridge response ok must be boolean');
  if (envelope.ok && 'error' in envelope) throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', 'Successful Bridge response cannot contain an error');
  if (!envelope.ok && !('error' in envelope)) throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', 'Failed Bridge response must contain an error');
  if (!envelope.ok && 'payload' in envelope) throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', 'Failed Bridge response cannot contain a payload');
  if ('payload' in envelope) validatePayload(envelope.payload);
  const id = requireString(envelope.id, 'Bridge response id');
  const error = 'error' in envelope ? validateBridgeError(envelope.error) : undefined;
  if (!envelope.ok && error?.requestId !== undefined && error.requestId !== id) {
    throw new BridgeValidationError('BRIDGE_PROTOCOL_INVALID', 'Bridge error requestId must match the response id');
  }
  const response: ResponseEnvelope = {
    version: BRIDGE_VERSION,
    type: 'response',
    id,
    ok: envelope.ok,
    ...('payload' in envelope ? { payload: envelope.payload } : {}),
    ...(error === undefined ? {} : { error }),
  };
  return response;
}
