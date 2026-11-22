import { describe, expect, it } from 'vitest';
import { canvasScaleForViewport, contentBottomInsetForScale } from './canvasScale';

describe('canvasScaleForViewport', () => {
  it('fits the 1920 by 1080 design canvas into TV WebView CSS viewports', () => {
    expect(canvasScaleForViewport(1920, 1080)).toBe(1);
    expect(canvasScaleForViewport(3840, 2160)).toBe(2);
    expect(canvasScaleForViewport(960, 540)).toBe(0.5);
    expect(canvasScaleForViewport(1280, 800)).toBeCloseTo(2 / 3);
  });

  it('falls back safely when the host has not measured a viewport yet', () => {
    expect(canvasScaleForViewport(0, 0)).toBe(1);
    expect(canvasScaleForViewport(Number.NaN, 1080)).toBe(1);
  });
});

describe('contentBottomInsetForScale', () => {
  it.each([0.25, 0.5, 2 / 3, 0.75, 1, 2])('reserves at least 120 viewport dp at scale %s', (scale) => {
    const inset = contentBottomInsetForScale(scale);
    expect(inset * scale).toBeGreaterThanOrEqual(120);
    expect(inset).toBeGreaterThanOrEqual(152);
  });

  it('converts the common 960px viewport without shrinking the original full-size inset', () => {
    expect(contentBottomInsetForScale(0.5)).toBe(240);
    expect(contentBottomInsetForScale(1)).toBe(152);
    expect(contentBottomInsetForScale(2)).toBe(152);
  });

  it.each([0, -1, Number.NaN, Number.POSITIVE_INFINITY])('handles an invalid scale %s', (scale) => {
    expect(contentBottomInsetForScale(scale)).toBe(152);
  });
});
