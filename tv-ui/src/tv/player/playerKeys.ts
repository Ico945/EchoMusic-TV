export const PLAYER_EVENTS = {
  snapshot: 'player.snapshot',
  position: 'player.position',
  queue: 'queue.changed',
  lyrics: 'lyrics.changed',
} as const;

export const PLAYER_COMMANDS = {
  playQueue: 'player.playQueue',
  toggle: 'player.toggle',
  previous: 'player.previous',
  next: 'player.next',
  seekBy: 'player.seekBy',
  retryCurrent: 'player.retryCurrent',
  clearError: 'player.clearError',
} as const;

export const NOW_PLAYING_ROUTE_NAME = 'now-playing' as const;

export const PLAYER_MEDIA_KEYS = new Set(['MediaPlayPause', 'MediaTrackPrevious', 'MediaTrackNext']);
