import { describe, expect, it } from 'vitest';
import { isTextEditingTarget, normalizeRemoteKey, remoteEventTarget } from './remoteKeys';

describe('remote key contract', () => {
  it.each(['Enter', 'NumpadEnter', 'Select', 'Accept'])('normalizes %s to Enter', (key) => {
    expect(normalizeRemoteKey(key)).toBe('Enter');
  });

  it.each(['Backspace', 'Escape', 'GoBack', 'BrowserBack'])('normalizes %s to GoBack', (key) => {
    expect(normalizeRemoteKey(key)).toBe('GoBack');
  });

  it('recognizes editable inputs without treating buttons as text editors', () => {
    const input = document.createElement('input');
    const textarea = document.createElement('textarea');
    const button = document.createElement('button');
    expect(isTextEditingTarget(input)).toBe(true);
    expect(isTextEditingTarget(textarea)).toBe(true);
    expect(isTextEditingTarget(button)).toBe(false);
    input.readOnly = true;
    expect(isTextEditingTarget(input)).toBe(false);
  });

  it('falls back to activeElement for events dispatched by a WebView host at window', () => {
    const input = document.createElement('input');
    document.body.append(input);
    input.focus();
    expect(remoteEventTarget(new KeyboardEvent('keydown', { key: 'Enter' }))).toBe(input);
    input.remove();
  });
});
