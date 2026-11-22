import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConfirmHoldDetector } from './confirmHold';

afterEach(() => vi.useRealTimers());

describe('ConfirmHoldDetector', () => {
  it('ignores orphan releases, canceled releases and repeats after cancellation', () => {
    vi.useFakeTimers();
    const short = vi.fn();
    const long = vi.fn();
    const detector = new ConfirmHoldDetector(short, long);
    detector.keyUp();
    detector.keyDown();
    detector.cancel();
    detector.keyDown(true);
    detector.keyUp();
    vi.advanceTimersByTime(1000);
    expect(short).not.toHaveBeenCalled();
    expect(long).not.toHaveBeenCalled();
    detector.keyDown();
    detector.keyUp();
    detector.keyUp();
    detector.keyDown();
    detector.keyUp();
    vi.advanceTimersByTime(1000);
    expect(short).toHaveBeenCalledTimes(2);
    expect(long).not.toHaveBeenCalled();
  });

  it('fires the short action when released before 650ms (catches a missing short action)', () => {
    vi.useFakeTimers();
    const short = vi.fn();
    const long = vi.fn();
    const detector = new ConfirmHoldDetector(short, long);

    detector.keyDown();
    vi.advanceTimersByTime(649);
    detector.keyUp();

    expect(short).toHaveBeenCalledTimes(1);
    expect(long).not.toHaveBeenCalled();
  });

  it('fires the long action at 650ms and suppresses short action on key-up (catches threshold and fired-state regressions)', () => {
    vi.useFakeTimers();
    const short = vi.fn();
    const long = vi.fn();
    const detector = new ConfirmHoldDetector(short, long);

    detector.keyDown();
    vi.advanceTimersByTime(650);
    detector.keyUp();

    expect(long).toHaveBeenCalledTimes(1);
    expect(short).not.toHaveBeenCalled();
  });

  it('does not reset an active hold on repeated key-down (catches repeated-key timer replacement)', () => {
    vi.useFakeTimers();
    const short = vi.fn();
    const long = vi.fn();
    const detector = new ConfirmHoldDetector(short, long);

    detector.keyDown();
    vi.advanceTimersByTime(400);
    detector.keyDown();
    vi.advanceTimersByTime(250);

    expect(long).toHaveBeenCalledTimes(1);
    expect(short).not.toHaveBeenCalled();
  });

  it('cancels a pending hold before its long action fires (catches an uncleared timer)', () => {
    vi.useFakeTimers();
    const short = vi.fn();
    const long = vi.fn();
    const detector = new ConfirmHoldDetector(short, long);

    detector.keyDown();
    detector.cancel();
    vi.advanceTimersByTime(650);

    expect(short).not.toHaveBeenCalled();
    expect(long).not.toHaveBeenCalled();
  });
});
