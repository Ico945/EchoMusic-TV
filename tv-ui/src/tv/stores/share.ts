import { defineStore } from 'pinia';
import { nativeClient } from '../native/client';

export type TvShareTarget = { kind: 'song' | 'playlist' | 'album'; id: string };
export type TvShareResult = { link: string; qrImageDataUrl: string; clipboardAvailable: boolean };

const SHARE_KINDS = ['song', 'playlist', 'album'] as const;

function record(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`${label} must be an object`);
  }
  return value as Record<string, unknown>;
}

function text(value: unknown, label: string): string {
  if (typeof value !== 'string') throw new Error(`${label} must be a string`);
  return value;
}

function parseTarget(value: unknown): TvShareTarget {
  const target = record(value, 'Share target');
  const kind = target.kind;
  if (typeof kind !== 'string' || !(SHARE_KINDS as readonly string[]).includes(kind)) {
    throw new Error('Share target kind is unsupported');
  }
  return { kind: kind as TvShareTarget['kind'], id: text(target.id, 'Share target id') };
}

function parseResult(value: unknown): TvShareResult {
  const result = record(value, 'Share result');
  if (typeof result.clipboardAvailable !== 'boolean') throw new Error('Share clipboardAvailable must be a boolean');
  return {
    link: text(result.link, 'Share link'),
    qrImageDataUrl: text(result.qrImageDataUrl, 'Share qr data url'),
    clipboardAvailable: result.clipboardAvailable,
  };
}

export const useTvShareStore = defineStore('tv-share', () => {
  async function resolve(link: string): Promise<TvShareTarget | null> {
    try {
      return parseTarget(await nativeClient.request<unknown>('share.resolve', { link }));
    } catch {
      return null;
    }
  }

  async function create(kind: string, id: string): Promise<TvShareResult | null> {
    try {
      return parseResult(await nativeClient.request<unknown>('share.create', { kind, id }));
    } catch {
      return null;
    }
  }

  return { resolve, create };
});
