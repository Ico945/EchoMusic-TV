export class ConfirmHoldDetector {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private fired = false;
  private pressed = false;

  constructor(
    private readonly onShort: () => void,
    private readonly onLong: () => void,
  ) {}

  keyDown(repeat = false): void {
    if (this.pressed || repeat) return;

    this.pressed = true;
    this.fired = false;
    this.timer = setTimeout(() => {
      this.fired = true;
      this.onLong();
    }, 650);
  }

  keyUp(): void {
    if (!this.pressed) return;
    this.pressed = false;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    if (!this.fired) this.onShort();
  }

  cancel(): void {
    this.pressed = false;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.fired = false;
  }
}
