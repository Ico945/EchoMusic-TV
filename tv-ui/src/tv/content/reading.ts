/** Scroll a long text card before handing its boundary back to spatial navigation. */
export function scrollReadingPanel(event: KeyboardEvent): void {
  if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return;
  const panel = event.currentTarget as HTMLElement;
  const maximum = Math.max(0, panel.scrollHeight - panel.clientHeight);
  const next = Math.max(0, Math.min(maximum, panel.scrollTop + (event.key === 'ArrowDown' ? 120 : -120)));
  if (Math.abs(next - panel.scrollTop) < 1) return;
  event.preventDefault();
  event.stopPropagation();
  panel.scrollTop = next;
}
