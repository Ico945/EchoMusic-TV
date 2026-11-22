import { describe, expect, it } from 'vitest';
import { enforceRenderBudget } from './renderBudget';

describe('renderBudget', () => {
  it('allows no more than two live blur surfaces', () => {
    expect(enforceRenderBudget({ blurSurfaces: 3, animatedBackgrounds: 1 })).toEqual({
      blurSurfaces: 2,
      animatedBackgrounds: 1,
      degraded: true,
    });
  });

  it('keeps an under-budget scene intact without degradation', () => {
    expect(enforceRenderBudget({ blurSurfaces: 1, animatedBackgrounds: 1 })).toEqual({
      blurSurfaces: 1,
      animatedBackgrounds: 1,
      degraded: false,
    });
  });

  it('caps animated backgrounds at one', () => {
    expect(enforceRenderBudget({ blurSurfaces: 0, animatedBackgrounds: 3 })).toEqual({
      blurSurfaces: 0,
      animatedBackgrounds: 1,
      degraded: true,
    });
  });

  it('clamps negative inputs to zero', () => {
    expect(enforceRenderBudget({ blurSurfaces: -1, animatedBackgrounds: -2 })).toEqual({
      blurSurfaces: 0,
      animatedBackgrounds: 0,
      degraded: true,
    });
  });
});
