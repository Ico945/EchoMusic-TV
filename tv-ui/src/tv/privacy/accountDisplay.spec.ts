import { describe, expect, it } from 'vitest';
import { accountMembershipLabel, privacySafeAccountLabel } from './accountDisplay';

describe('shared-screen account labels', () => {
  it.each(['', '用户408615481', '408615481', '#408615481'])(
    'hides the fallback account identifier %j',
    (value) => expect(privacySafeAccountLabel(value)).toBe('酷狗用户'),
  );

  it('preserves a real nickname', () => {
    expect(privacySafeAccountLabel('Echo User')).toBe('Echo User');
  });

  it.each([undefined, '', 'none', 'NONE'])(
    'presents the non-member value %j as a user-facing label',
    (value) => expect(accountMembershipLabel(value)).toBe('普通用户'),
  );

  it('preserves a named membership tier', () => {
    expect(accountMembershipLabel('VIP')).toBe('VIP');
  });
});
