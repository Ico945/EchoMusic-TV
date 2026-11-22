import type { TvMvDetail, TvRouteId, TvSession, TvSessionStatus, TvSnapshot, TvTrack } from './app';
import type { TvHome, TvShelf } from './content';
import { privacySafeAccountLabel } from '../privacy/accountDisplay';

type JsonRecord = Record<string, unknown>;

const sessionStatuses: readonly TvSessionStatus[] = ['loggedOut', 'expired', 'qrPending', 'loggedIn'];
const tvRouteIds: readonly TvRouteId[] = ['home', 'explore', 'fm', 'ranking', 'library', 'search', 'more'];
const mvQualities = ['fhd', 'hd', 'qhd', 'sd', 'ld'] as const;

export class TvPayloadValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TvPayloadValidationError';
  }
}

function record(value: unknown, label: string): JsonRecord {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new TvPayloadValidationError(`${label} must be an object`);
  }
  return value as JsonRecord;
}

function string(value: unknown, label: string, nonEmpty = false): string {
  if (typeof value !== 'string' || (nonEmpty && value.trim().length === 0)) {
    throw new TvPayloadValidationError(`${label} must be${nonEmpty ? ' a non-empty' : ' a'} string`);
  }
  return value;
}

function array(value: unknown, label: string): unknown[] {
  if (!Array.isArray(value)) throw new TvPayloadValidationError(`${label} must be an array`);
  return value;
}

function enumString<T extends string>(value: unknown, label: string, allowed: readonly T[]): T {
  const parsed = string(value, label, true);
  if (!allowed.includes(parsed as T)) throw new TvPayloadValidationError(`${label} is unsupported`);
  return parsed as T;
}

function duration(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new TvPayloadValidationError('Track durationMs must be a finite non-negative number');
  }
  return value;
}

export function parseTvTrack(value: unknown): TvTrack {
  const track = record(value, 'Track');
  return {
    id: string(track.id, 'Track id', true),
    title: string(track.title, 'Track title', true),
    artist: string(track.artist, 'Track artist'),
    album: string(track.album, 'Track album'),
    artwork: string(track.artwork, 'Track artwork'),
    durationMs: duration(track.durationMs),
    ...(track.mv === undefined ? {} : { mv: parseTvMvDetail(track.mv) }),
  };
}

function parseTvMvDetail(value: unknown): TvMvDetail {
  const mv = record(value, 'Track MV');
  const id = string(mv.id, 'Track MV id', true);
  if (!/^mv:[A-Za-z0-9_-]{1,120}$/.test(id)) throw new TvPayloadValidationError('Track MV id is unsupported');
  const quality = enumString(mv.quality, 'Track MV quality', mvQualities);
  const qualities = array(mv.qualities, 'Track MV qualities').map((item) => enumString(item, 'Track MV quality', mvQualities));
  if (!qualities.includes(quality)) throw new TvPayloadValidationError('Track MV quality is unavailable');
  return {
    id,
    title: string(mv.title, 'Track MV title', true),
    artist: string(mv.artist, 'Track MV artist'),
    quality,
    qualities: [...new Set(qualities)],
  };
}

export function parseTvShelf(value: unknown): TvShelf {
  const shelf = record(value, 'Shelf');
  return {
    id: string(shelf.id, 'Shelf id', true),
    title: string(shelf.title, 'Shelf title', true),
    capability: string(shelf.capability, 'Shelf capability'),
    tracks: array(shelf.tracks, 'Shelf tracks').map(parseTvTrack),
  };
}

export function parseTvHome(value: unknown): TvHome {
  const home = record(value, 'Home');
  return {
    greeting: string(home.greeting, 'Home greeting'),
    hero: parseTvTrack(home.hero),
    secondary: array(home.secondary, 'Home secondary').map(parseTvTrack),
    shelves: array(home.shelves, 'Home shelves').map(parseTvShelf),
  };
}

export function parseTvSession(value: unknown): TvSession {
  const session = record(value, 'Session');
  return {
    status: enumString(session.status, 'Session status', sessionStatuses),
    ...(session.displayName === undefined
      ? {}
      : { displayName: privacySafeAccountLabel(string(session.displayName, 'Session displayName')) }),
    ...(session.membership === undefined ? {} : { membership: string(session.membership, 'Session membership') }),
  };
}

export function parseTvSnapshot(value: unknown): TvSnapshot {
  const snapshot = record(value, 'Snapshot');
  return {
    route: enumString(snapshot.route, 'Snapshot route', tvRouteIds),
    session: parseTvSession(snapshot.session),
    ...(snapshot.bootstrapHome === undefined ? {} : { bootstrapHome: parseTvHome(snapshot.bootstrapHome) }),
  };
}
