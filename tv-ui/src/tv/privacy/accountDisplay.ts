const PRIVATE_ACCOUNT_LABEL = '酷狗用户';

/**
 * Older native builds used the numeric KuGou id when the upstream profile had
 * no nickname. Keep that identifier off a shared television display even if a
 * stale encrypted snapshot or malformed future payload reaches the renderer.
 */
export function privacySafeAccountLabel(value: string): string {
  const candidate = value.trim();
  if (!candidate || /^用户\d{4,}$/u.test(candidate) || /^#?\d{4,}$/u.test(candidate)) {
    return PRIVATE_ACCOUNT_LABEL;
  }
  return candidate;
}

export function accountMembershipLabel(value: string | undefined): string {
  const candidate = value?.trim() ?? '';
  return !candidate || candidate.toLowerCase() === 'none' ? '普通用户' : candidate;
}
