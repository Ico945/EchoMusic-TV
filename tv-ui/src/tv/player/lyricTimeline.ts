import type { LyricLine } from '../stores/player';

export function activeLyricIndex(lines: Pick<LyricLine, 'startMs'>[], positionMs: number): number {
  let result = -1;
  for (let index = 0; index < lines.length; index += 1) {
    if (lines[index]!.startMs > positionMs) break;
    result = index;
  }
  return result;
}

export function shouldResumeFollow(manualAtMs: number | null, nowMs: number): boolean {
  return manualAtMs === null || nowMs - manualAtMs >= 3_000;
}
