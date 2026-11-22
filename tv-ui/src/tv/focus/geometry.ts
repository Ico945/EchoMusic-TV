import type { Direction, FocusBox } from './types';

export function chooseNext(
  origin: FocusBox,
  candidates: FocusBox[],
  direction: Direction,
): FocusBox | null {
  const horizontal = direction === 'left' || direction === 'right';
  let best: FocusBox | null = null;
  let bestScore = Infinity;
  // Select only the winner instead of allocating and sorting all candidates.
  for (const candidate of candidates) {
    const dx = candidate.centerX - origin.centerX;
    const dy = candidate.centerY - origin.centerY;
    const forward = direction === 'right' ? dx > 0 : direction === 'left' ? dx < 0
      : direction === 'down' ? dy > 0 : dy < 0;
    if (!forward) continue;
    const score = (horizontal ? Math.abs(dx) : Math.abs(dy))
      + (horizontal ? Math.abs(dy) : Math.abs(dx)) * 2.25;
    if (!best || score < bestScore || (score === bestScore && candidate.id < best.id)) {
      best = candidate;
      bestScore = score;
    }
  }
  return best;
}
