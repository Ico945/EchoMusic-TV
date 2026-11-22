import { ref } from 'vue';
import { defineStore } from 'pinia';
import { nativeClient } from '../native/client';
import { privacySafeAccountLabel } from '../privacy/accountDisplay';

export type TvAuthState = 'loggedOut' | 'qrPending' | 'scanned' | 'expired' | 'loggedIn';

export type TvAuthAccount = {
  displayName: string;
  membership: string;
};

const authStates = ['loggedOut', 'qrPending', 'scanned', 'expired', 'loggedIn'] as const;

type AuthProjection = {
  state: TvAuthState;
  qrImageDataUrl: string;
  expiresAt: number | null;
  account: { displayName: string; membership: string } | null;
};

function parseProjection(value: unknown): AuthProjection {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error('auth projection must be an object');
  }
  const record = value as Record<string, unknown>;
  const state = record.state;
  if (typeof state !== 'string' || !(authStates as readonly string[]).includes(state)) {
    throw new Error('auth state is unsupported');
  }
  const rawAccount = record.account;
  let account: AuthProjection['account'] = null;
  if (rawAccount !== null && rawAccount !== undefined) {
    if (typeof rawAccount !== 'object' || Array.isArray(rawAccount)) throw new Error('auth account is invalid');
    const accountRecord = rawAccount as Record<string, unknown>;
    if (typeof accountRecord.nickname !== 'string' || typeof accountRecord.membership !== 'string') {
      throw new Error('auth account is invalid');
    }
    account = {
      displayName: privacySafeAccountLabel(accountRecord.nickname),
      membership: accountRecord.membership,
    };
  }
  const qrImageDataUrl = typeof record.qrImageDataUrl === 'string' ? record.qrImageDataUrl : '';
  const expiresAt = typeof record.expiresAt === 'number' ? record.expiresAt : null;
  return { state: state as TvAuthState, qrImageDataUrl, expiresAt, account };
}

export const useTvAuthStore = defineStore('tv-auth', () => {
  const state = ref<TvAuthState>('loggedOut');
  const account = ref<TvAuthAccount | null>(null);
  const qrImageDataUrl = ref('');
  const expiresAt = ref<number | null>(null);
  const lastError = ref<{ code: string; message: string } | null>(null);
  let commandGeneration = 0;
  let eventRevision = 0;
  let acceptingQrEvents = false;

  function captureError(error: unknown): void {
    if (typeof error === 'object' && error !== null && 'code' in error) {
      const record = error as { code?: unknown; message?: unknown };
      lastError.value = {
        code: typeof record.code === 'string' ? record.code : 'UNKNOWN',
        message: typeof record.message === 'string' ? record.message : '',
      };
    } else {
      lastError.value = { code: 'UNKNOWN', message: String(error) };
    }
  }

  function applyProjection(value: unknown): void {
    const projection = parseProjection(value);
    state.value = projection.state;
    account.value = projection.account;
    qrImageDataUrl.value = projection.qrImageDataUrl;
    expiresAt.value = projection.expiresAt;
  }

  async function requestAuth(method: 'auth.beginQr' | 'auth.cancelQr' | 'auth.restore' | 'auth.logout'): Promise<void> {
    const generation = ++commandGeneration;
    const revision = eventRevision;
    acceptingQrEvents = method === 'auth.beginQr';
    lastError.value = null;
    if ((method === 'auth.beginQr' || method === 'auth.cancelQr') && state.value !== 'loggedIn') {
      applyProjection({ state: 'loggedOut' });
    }
    try {
      const projection = await nativeClient.request<unknown>(method, {});
      if (generation !== commandGeneration) return;
      // A poll event may be newer than a delayed begin reply.
      if (revision === eventRevision) applyProjection(projection);
      lastError.value = null;
      if (state.value !== 'qrPending' && state.value !== 'scanned') acceptingQrEvents = false;
    } catch (error) {
      if (generation !== commandGeneration) return;
      acceptingQrEvents = false;
      captureError(error);
      throw error;
    }
  }

  const beginQr = () => requestAuth('auth.beginQr');
  const cancelQr = () => requestAuth('auth.cancelQr');
  const restore = () => requestAuth('auth.restore');
  const logout = () => requestAuth('auth.logout');

  function connect(): () => void {
    const unsubscribe = nativeClient.on('auth.stateChanged', (payload) => {
      if (!acceptingQrEvents) return;
      try {
        applyProjection(payload);
        eventRevision += 1;
        if (state.value !== 'qrPending' && state.value !== 'scanned') acceptingQrEvents = false;
      } catch {
        // Keep the last valid projection on malformed events.
      }
    });
    return unsubscribe;
  }

  return { state, account, qrImageDataUrl, expiresAt, lastError, applyProjection, beginQr, cancelQr, restore, logout, connect };
});
