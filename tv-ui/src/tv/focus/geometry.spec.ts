import { describe, expect, it } from 'vitest';
import { chooseNext } from './geometry';

const box = (id: string, left: number, top: number, width = 100, height = 60) => ({
  id,
  left,
  top,
  right: left + width,
  bottom: top + height,
  centerX: left + width / 2,
  centerY: top + height / 2,
});

describe('chooseNext', () => {
  it.each(['up', 'down', 'left', 'right'] as const)('preserves score and id ordering for dense grids moving %s', (direction) => {
    const origin = box('origin', 300, 200);
    const candidates = Array.from({ length: 400 }, (_, index) =>
      box(`item-${399 - index}`, (index % 20) * 50, Math.floor(index / 20) * 40));
    const horizontal = direction === 'left' || direction === 'right';
    const forward = direction === 'down' || direction === 'right';
    const expected = candidates.filter((candidate) => {
      const distance = horizontal ? candidate.centerX - origin.centerX : candidate.centerY - origin.centerY;
      return forward ? distance > 0 : distance < 0;
    }).map((candidate) => {
      const dx = Math.abs(candidate.centerX - origin.centerX);
      const dy = Math.abs(candidate.centerY - origin.centerY);
      return { candidate, score: horizontal ? dx + 2.25 * dy : dy + 2.25 * dx };
    }).sort((a, b) => a.score - b.score || (a.candidate.id < b.candidate.id ? -1 : 1))[0].candidate;
    expect(chooseNext(origin, candidates, direction)).toBe(expected);
    expect(chooseNext(origin, [...candidates].reverse(), direction)).toBe(expected);
  });

  it('prefers the closest candidate in the requested half-plane (catches reversed scoring)', () => {
    expect(chooseNext(box('a', 0, 0), [box('b', 140, 0), box('c', 120, 90)], 'right')?.id).toBe(
      'b',
    );
  });

  it('does not return a candidate behind the requested direction (catches missing half-plane filtering)', () => {
    expect(chooseNext(box('a', 100, 0), [box('b', 0, 0)], 'right')).toBeNull();
  });

  it('returns null for an empty candidate set (catches an undefined focus target)', () => {
    expect(chooseNext(box('a', 0, 0), [], 'down')).toBeNull();
  });

  it('resolves equal scores by logical id regardless of candidate order (catches sort-order ties)', () => {
    const origin = box('a', 0, 0);
    const candidates = [box('c', 140, 60), box('b', 140, 60)];

    expect(chooseNext(origin, candidates, 'right')?.id).toBe('b');
  });
});
