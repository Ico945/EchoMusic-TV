import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { nativeClient } from '../native/client';
import { PLAYER_COMMANDS, PLAYER_EVENTS } from '../player/playerKeys';

export type PlayerTrack = {
  id: string;
  title: string;
  artists: string[];
  album: string;
  durationMs: number;
  qualities: string[];
  artworkUrl?: string;
};

export type LyricLine = { startMs: number; endMs: number; text: string };
export type RepeatMode = 'off' | 'all' | 'one';
export type PlayerCommandError = { code: string; message: string; retryable: boolean; errorId: number };

export class PlayerCommandRejectedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PlayerCommandRejectedError';
  }
}

export type PlayerSnapshot = {
  buffering?: boolean;
  currentTrack: PlayerTrack | null;
  isPlaying: boolean;
  positionMs: number;
  durationMs: number;
  queue: PlayerTrack[];
  queueIndex: number;
  repeatMode: RepeatMode;
  quality: string;
  lyrics: LyricLine[];
  error: PlayerCommandError | null;
  errorRevision: number;
};

type JsonRecord = Record<string, unknown>;
type AppError = { errorId: number; error: PlayerCommandError };

function record(value: unknown, label: string): JsonRecord {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error(`${label} must be an object`);
  return value as JsonRecord;
}

function exactFields(source: JsonRecord, allowed: readonly string[], required: readonly string[], label: string): void {
  const actual = Object.keys(source);
  if (actual.some((key) => !allowed.includes(key)) || required.some((key) => !(key in source))) {
    throw new Error(`${label} fields are invalid`);
  }
}

function string(value: unknown, label: string, allowEmpty = false): string {
  if (typeof value !== 'string' || (!allowEmpty && value.trim().length === 0)) throw new Error(`${label} must be a string`);
  return value;
}

function integer(value: unknown, label: string, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) throw new Error(`${label} must be an integer`);
  return value;
}

function strings(value: unknown, label: string): string[] {
  if (!Array.isArray(value)) throw new Error(`${label} must be an array`);
  return value.map((item, index) => string(item, `${label}[${index}]`));
}

function artworkUrl(value: unknown): string | undefined {
  if (value === undefined) return undefined;
  const raw = string(value, 'Track artworkUrl');
  const parsed = new URL(raw);
  if (
    parsed.protocol !== 'https:' ||
    !['imge.kugou.com', 'singerimg.kugou.com'].includes(parsed.hostname) ||
    // URL normalizes explicit :443 and empty query/fragment markers. Check
    // the raw authority too, matching Android's exact artwork policy.
    !/^https:\/\/(?:imge|singerimg)\.kugou\.com(?:\/[^?#\\\\]*)?$/i.test(raw) ||
    parsed.username ||
    parsed.password ||
    parsed.port ||
    parsed.search ||
    parsed.hash
  ) {
    throw new Error('Track artworkUrl is not allowed');
  }
  return parsed.toString();
}

function parseTrack(value: unknown): PlayerTrack {
  const source = record(value, 'Track');
  exactFields(
    source,
    ['id', 'title', 'artists', 'album', 'durationMs', 'qualities', 'artworkUrl'],
    ['id', 'title', 'artists', 'album', 'durationMs', 'qualities'],
    'Track',
  );
  const artwork = artworkUrl(source.artworkUrl);
  return {
    id: string(source.id, 'Track id'),
    title: string(source.title, 'Track title'),
    artists: strings(source.artists, 'Track artists'),
    album: string(source.album, 'Track album', true),
    durationMs: integer(source.durationMs, 'Track durationMs'),
    qualities: strings(source.qualities, 'Track qualities'),
    ...(artwork === undefined ? {} : { artworkUrl: artwork }),
  };
}

function parseLyrics(value: unknown): LyricLine[] {
  if (!Array.isArray(value)) throw new Error('Lyrics must be an array');
  return value.map((item) => {
    const line = record(item, 'Lyric line');
    exactFields(line, ['startMs', 'endMs', 'text'], ['startMs', 'endMs', 'text'], 'Lyric line');
    const startMs = integer(line.startMs, 'Lyric startMs');
    const endMs = integer(line.endMs, 'Lyric endMs');
    if (endMs < startMs) throw new Error('Lyric endMs must not precede startMs');
    return { startMs, endMs, text: string(line.text, 'Lyric text', true) };
  });
}

function parseQueue(value: unknown): PlayerTrack[] {
  if (!Array.isArray(value)) throw new Error('Queue must be an array');
  return value.map(parseTrack);
}

function parseError(value: unknown): PlayerCommandError | null {
  if (value === null) return null;
  const source = record(value, 'Player error');
  exactFields(source, ['code', 'message', 'retryable', 'errorId'], ['code', 'message', 'retryable', 'errorId'], 'Player error');
  if (typeof source.retryable !== 'boolean') throw new Error('Player error retryable must be a boolean');
  return {
    code: string(source.code, 'Player error code'),
    message: string(source.message, 'Player error message'),
    retryable: source.retryable,
    errorId: integer(source.errorId, 'Player errorId', 1),
  };
}

function parseAppError(value: unknown): AppError {
  const source = record(value, 'App error');
  const error = parseError(source);
  if (error === null) throw new Error('App error must not be null');
  return { errorId: error.errorId, error };
}

function parseSnapshot(value: unknown): PlayerSnapshot {
  const source = record(value, 'Player snapshot');
  exactFields(
    source,
    ['currentTrack', 'isPlaying', 'positionMs', 'durationMs', 'queue', 'queueIndex', 'repeatMode', 'quality', 'lyrics', 'error', 'errorRevision', 'buffering'],
    ['currentTrack', 'isPlaying', 'positionMs', 'durationMs', 'queue', 'queueIndex', 'repeatMode', 'quality', 'lyrics', 'error', 'errorRevision'],
    'Player snapshot',
  );
  const repeatMode = string(source.repeatMode, 'Player repeatMode');
  if (!['off', 'all', 'one'].includes(repeatMode)) throw new Error('Player repeatMode is unsupported');
  if (typeof source.isPlaying !== 'boolean') throw new Error('Player isPlaying must be a boolean');
  if (source.buffering !== undefined && typeof source.buffering !== 'boolean') throw new Error('Player buffering must be a boolean');
  const queue = parseQueue(source.queue);
  const queueIndex = integer(source.queueIndex, 'Player queueIndex', -1);
  if (queueIndex >= queue.length && queueIndex !== -1) throw new Error('Player queueIndex is out of range');
  const error = parseError(source.error);
  const errorRevision = integer(source.errorRevision, 'Player errorRevision');
  if (error !== null && error.errorId !== errorRevision) throw new Error('Player error identity does not match its revision');
  return {
    currentTrack: source.currentTrack === null ? null : parseTrack(source.currentTrack),
    isPlaying: source.isPlaying,
    buffering: source.buffering === true,
    positionMs: integer(source.positionMs, 'Player positionMs'),
    durationMs: integer(source.durationMs, 'Player durationMs'),
    queue,
    queueIndex,
    repeatMode: repeatMode as RepeatMode,
    quality: string(source.quality, 'Player quality', true),
    lyrics: parseLyrics(source.lyrics),
    error,
    errorRevision,
  };
}

export const useTvPlayerStore = defineStore('tv-player', () => {
  const currentTrack = ref<PlayerTrack | null>(null);
  const isPlaying = ref(false);
  const buffering = ref(false);
  const pendingOperations = ref(0);
  const statusLabel = computed(() => pendingOperations.value ? '正在处理…' : error.value ? '播放失败 · 确认查看' : buffering.value ? '正在缓冲…' : isPlaying.value ? '正在播放' : '已暂停');
  const positionMs = ref(0);
  const durationMs = ref(0);
  const queue = ref<PlayerTrack[]>([]);
  const queueIndex = ref(-1);
  const repeatMode = ref<RepeatMode>('off');
  const quality = ref('');
  const lyrics = ref<LyricLine[]>([]);
  const error = ref<PlayerCommandError | null>(null);
  const commandFailure = ref<string | null>(null);
  const progressRatio = computed(() => (durationMs.value > 0 ? Math.min(1, Math.max(0, positionMs.value / durationMs.value)) : 0));
  let unsubscribe: Array<() => void> = [];
  let connectionLeases = 0;
  let latestErrorRevision = 0;

  function applyNativeError(nextError: PlayerCommandError | null, revision: number): boolean {
    if (revision < latestErrorRevision) return false;
    if (revision === latestErrorRevision) {
      if (error.value?.errorId === nextError?.errorId || (error.value === null && nextError === null)) return true;
      return false;
    }
    latestErrorRevision = revision;
    error.value = nextError;
    return true;
  }

  function applySnapshot(payload: unknown): boolean {
    try {
      const next = parseSnapshot(payload);
      currentTrack.value = next.currentTrack;
      isPlaying.value = next.isPlaying;
      buffering.value = next.buffering ?? false;
      positionMs.value = next.positionMs;
      durationMs.value = next.durationMs;
      queue.value = next.queue;
      queueIndex.value = next.queueIndex;
      repeatMode.value = next.repeatMode;
      quality.value = next.quality;
      lyrics.value = next.lyrics;
      applyNativeError(next.error, next.errorRevision);
      commandFailure.value = null;
      return true;
    } catch {
      return false;
    }
  }

  function applyPosition(payload: unknown): boolean {
    try {
      const source = record(payload, 'Player position');
      exactFields(source, ['positionMs', 'durationMs'], ['positionMs', 'durationMs'], 'Player position');
      const nextPosition = integer(source.positionMs, 'Player positionMs');
      const nextDuration = integer(source.durationMs, 'Player durationMs');
      positionMs.value = nextPosition;
      durationMs.value = nextDuration;
      return true;
    } catch {
      return false;
    }
  }

  function applyQueue(payload: unknown): boolean {
    try {
      const source = record(payload, 'Player queue');
      exactFields(source, ['queue', 'queueIndex'], ['queue', 'queueIndex'], 'Player queue');
      const nextQueue = parseQueue(source.queue);
      const nextIndex = integer(source.queueIndex, 'Player queueIndex', -1);
      if (nextIndex >= nextQueue.length && nextIndex !== -1) return false;
      queue.value = nextQueue;
      queueIndex.value = nextIndex;
      return true;
    } catch {
      return false;
    }
  }

  function applyLyrics(payload: unknown): boolean {
    try {
      const source = record(payload, 'Player lyrics');
      exactFields(source, ['lyrics'], ['lyrics'], 'Player lyrics');
      lyrics.value = parseLyrics(source.lyrics);
      return true;
    } catch {
      return false;
    }
  }

  function applyAppError(payload: unknown): boolean {
    try {
      const next = parseAppError(payload);
      return applyNativeError(next.error, next.errorId);
    } catch {
      return false;
    }
  }

  function connect(): () => void {
    connectionLeases += 1;
    if (unsubscribe.length === 0) {
      unsubscribe = [
        nativeClient.on(PLAYER_EVENTS.snapshot, applySnapshot),
        nativeClient.on(PLAYER_EVENTS.position, applyPosition),
        nativeClient.on(PLAYER_EVENTS.queue, applyQueue),
        nativeClient.on(PLAYER_EVENTS.lyrics, applyLyrics),
        nativeClient.on('app.error', applyAppError),
      ];
    }
    let released = false;
    return () => {
      if (released) return;
      released = true;
      connectionLeases = Math.max(0, connectionLeases - 1);
      if (connectionLeases === 0) releaseNativeProjection();
    };
  }

  function releaseNativeProjection(): void {
    unsubscribe.forEach((release) => release());
    unsubscribe = [];
  }

  async function runCommand(method: string, payload: unknown): Promise<void> {
    pendingOperations.value++;
    try {
      const response = await nativeClient.request<unknown>(method, payload);
      const result = record(response, 'Player command response');
      exactFields(result, ['accepted'], ['accepted'], 'Player command response');
      if (typeof result.accepted !== 'boolean') throw new Error('Player command response is invalid');
      if (!result.accepted) throw new PlayerCommandRejectedError('播放器暂未就绪，请稍后重试');
      commandFailure.value = null;
    } catch (reason) {
      const message = reason instanceof PlayerCommandRejectedError ? reason.message : '操作失败，请重试';
      commandFailure.value = message;
      throw new PlayerCommandRejectedError(message);
    } finally {
      pendingOperations.value = Math.max(0, pendingOperations.value - 1);
    }
  }

  async function playQueue(trackIds: string[], startIndex: number): Promise<void> {
    await runCommand(PLAYER_COMMANDS.playQueue, { trackIds, startIndex });
  }

  async function playNext(trackId: string): Promise<void> {
    await runCommand('player.playNext', { trackId });
  }

  async function toggle(): Promise<void> {
    await runCommand(PLAYER_COMMANDS.toggle, {});
  }

  async function previous(): Promise<void> {
    await runCommand(PLAYER_COMMANDS.previous, {});
  }

  async function next(): Promise<void> {
    await runCommand(PLAYER_COMMANDS.next, {});
  }

  async function seekBy(deltaMs: -10_000 | 10_000): Promise<void> {
    await runCommand(PLAYER_COMMANDS.seekBy, { deltaMs });
  }

  async function selectQueueItem(trackId: string): Promise<void> {
    await runCommand('player.selectQueueItem', { trackId });
  }

  async function seekTo(trackId: string, positionMs: number): Promise<void> {
    await runCommand('player.seekTo', { trackId, positionMs });
  }

  async function setRepeatMode(mode: RepeatMode): Promise<void> {
    await runCommand('player.setRepeatMode', { mode });
  }

  async function editQueue(action: 'shuffle' | 'remove' | 'clearPending', trackId?: string): Promise<void> {
    await runCommand('player.editQueue', { action, ...(action === 'remove' ? { trackId } : {}) });
  }

  async function setQuality(nextQuality: string): Promise<void> {
    if (!currentTrack.value?.qualities.includes(nextQuality)) throw new PlayerCommandRejectedError('当前歌曲没有此音质');
    await runCommand('player.setQuality', { quality: nextQuality });
  }

  async function setSoundEffect(preset: 'off' | 'bass' | 'vocal' | 'live'): Promise<void> {
    await runCommand('player.setSoundEffect', { preset });
  }

  async function retryCurrent(): Promise<void> {
    await runCommand(PLAYER_COMMANDS.retryCurrent, {});
  }

  async function clearError(): Promise<void> {
    const current = error.value;
    if (current === null) return;
    const response = await nativeClient.request<unknown>(PLAYER_COMMANDS.clearError, { errorId: current.errorId });
    const result = record(response, 'Player clear error response');
    exactFields(result, ['accepted', 'errorRevision'], ['accepted', 'errorRevision'], 'Player clear error response');
    if (typeof result.accepted !== 'boolean') throw new Error('Player clear error accepted must be a boolean');
    const revision = integer(result.errorRevision, 'Player clear errorRevision');
    if (result.accepted) applyNativeError(null, revision);
  }

  return {
    currentTrack,
    isPlaying,
    buffering,
    statusLabel,
    pendingOperations,
    positionMs,
    durationMs,
    queue,
    queueIndex,
    repeatMode,
    quality,
    lyrics,
    error,
    commandFailure,
    progressRatio,
    applySnapshot,
    applyPosition,
    applyQueue,
    applyLyrics,
    applyAppError,
    connect,
    playQueue,
    playNext,
    toggle,
    previous,
    next,
    seekBy,
    seekTo,
    selectQueueItem,
    setRepeatMode,
    editQueue,
    setQuality,
    setSoundEffect,
    retryCurrent,
    clearError,
  };
});
