import { describe, expect, it } from 'vitest';
import { activeLyricIndex, shouldResumeFollow } from './lyricTimeline';

const lines = [
  { startMs: 0, endMs: 1_199, text: 'a' },
  { startMs: 1_200, endMs: 2_799, text: 'b' },
  { startMs: 2_800, endMs: 4_000, text: 'c' },
];

describe('lyric timeline', () => {
  it('selects the last line whose timestamp is not after playback', () => {
    expect(activeLyricIndex(lines, -1)).toBe(-1);
    expect(activeLyricIndex(lines, 0)).toBe(0);
    expect(activeLyricIndex(lines, 2_100)).toBe(1);
    expect(activeLyricIndex(lines, 9_000)).toBe(2);
  });

  it('resumes following exactly three seconds after manual browsing', () => {
    expect(shouldResumeFollow(5_000, 7_999)).toBe(false);
    expect(shouldResumeFollow(5_000, 8_000)).toBe(true);
    expect(shouldResumeFollow(null, 8_000)).toBe(true);
  });
});
