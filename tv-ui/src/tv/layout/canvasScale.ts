const DESIGN_WIDTH = 1920;
const DESIGN_HEIGHT = 1080;

export function canvasScaleForViewport(width: number, height: number): number {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return 1;
  return Math.min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT);
}

// IME resize changes the usable height, not the television's design scale.
export function viewportWithKeyboard(width: number, height: number, previous: { width: number; height: number }, editing: boolean) {
  const keyboard = editing && Math.abs(width - previous.width) < 2 && height < previous.height * .8;
  return { width, height: keyboard ? previous.height : height, keyboard };
}

/** With the device-width viewport and zoom disabled, viewport CSS px are Android dp.
 * Convert the 120dp minimum back into design space before scaling the whole canvas.
 * Keep the original 152px clearance when that already exceeds the minimum.
 */
export function contentBottomInsetForScale(scale: number): number {
  const safeScale = Number.isFinite(scale) && scale > 0 ? scale : 1;
  return Math.max(152, Math.ceil(120 / safeScale));
}
