export type RenderBudget = {
  blurSurfaces: number;
  animatedBackgrounds: number;
  degraded: boolean;
};

export type RenderBudgetInput = {
  blurSurfaces: number;
  animatedBackgrounds: number;
};

const MAX_LIVE_BLUR_SURFACES = 2;
const MAX_ANIMATED_BACKGROUNDS = 1;

export function enforceRenderBudget(input: RenderBudgetInput): RenderBudget {
  const blurSurfaces = Math.min(Math.max(0, Math.floor(input.blurSurfaces)), MAX_LIVE_BLUR_SURFACES);
  const animatedBackgrounds = Math.min(Math.max(0, Math.floor(input.animatedBackgrounds)), MAX_ANIMATED_BACKGROUNDS);
  return {
    blurSurfaces,
    animatedBackgrounds,
    degraded: blurSurfaces !== input.blurSurfaces || animatedBackgrounds !== input.animatedBackgrounds,
  };
}

export const FALLBACK_GLASS = 'rgba(20,27,39,.92)' as const;
