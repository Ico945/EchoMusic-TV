import { ref } from 'vue';
import { defineStore } from 'pinia';
import { nativeClient } from '../native/client';
import { isNativeBridgeAvailable } from '../native/transport';

export type TvSettings = {
  reducedMotion: boolean;
  defaultQuality: string;
  lyricFontScale: number;
  lyricBackground: 'blur' | 'gradient';
  cacheLimitMb: number;
  compatiblePluginsEnabled: boolean;
};

const SETTING_KEYS = [
  'reducedMotion',
  'defaultQuality',
  'lyricFontScale',
  'lyricBackground',
  'cacheLimitMb',
  'compatiblePluginsEnabled',
] as const;
export type SettingKey = (typeof SETTING_KEYS)[number];

const QUALITIES = ['128', '320', 'flac'] as const;

const defaults: TvSettings = {
  reducedMotion: false,
  defaultQuality: '128',
  lyricFontScale: 100,
  lyricBackground: 'blur',
  cacheLimitMb: 1024,
  compatiblePluginsEnabled: true,
};

function parseSettings(value: unknown): TvSettings {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('settings must be an object');
  const record = value as Record<string, unknown>;
  if (typeof record.reducedMotion !== 'boolean') throw new Error('reducedMotion must be a boolean');
  const quality = record.defaultQuality;
  if (typeof quality !== 'string' || !(QUALITIES as readonly string[]).includes(quality)) throw new Error('defaultQuality is unsupported');
  const fontScale = record.lyricFontScale;
  if (typeof fontScale !== 'number' || fontScale < 80 || fontScale > 160) throw new Error('lyricFontScale is invalid');
  const background = record.lyricBackground;
  if (background !== 'blur' && background !== 'gradient') throw new Error('lyricBackground is unsupported');
  const cacheLimitMb = record.cacheLimitMb;
  if (typeof cacheLimitMb !== 'number' || cacheLimitMb < 64 || cacheLimitMb > 4096) throw new Error('cacheLimitMb is invalid');
  if (typeof record.compatiblePluginsEnabled !== 'boolean') throw new Error('compatiblePluginsEnabled must be a boolean');
  return {
    reducedMotion: record.reducedMotion,
    defaultQuality: quality,
    lyricFontScale: fontScale,
    lyricBackground: background,
    cacheLimitMb,
    compatiblePluginsEnabled: record.compatiblePluginsEnabled,
  };
}

export const useTvSettingsStore = defineStore('tv-settings', () => {
  const settings = ref<TvSettings>({ ...defaults });
  const loaded = ref(false);
  const cacheBytes = ref(0);
  const lastError = ref(false);

  function apply(value: unknown): void {
    settings.value = parseSettings(value);
    loaded.value = true;
  }

  async function load(): Promise<void> {
    if (!isNativeBridgeAvailable()) {
      settings.value = { ...defaults };
      loaded.value = true;
      return;
    }
    try {
      const payload = await nativeClient.request<unknown>('settings.get', {});
      const record = payload as Record<string, unknown>;
      if (typeof record.cacheBytes === 'number') cacheBytes.value = record.cacheBytes;
      apply(record.settings);
      lastError.value = false;
    } catch {
      lastError.value = true;
    }
  }

  async function update(key: SettingKey, value: unknown): Promise<boolean> {
    const previous = settings.value[key];
    settings.value = { ...settings.value, [key]: value };
    try {
      const payload = await nativeClient.request<unknown>('settings.update', { key, value });
      const record = payload as Record<string, unknown>;
      if (typeof record.cacheBytes === 'number') cacheBytes.value = record.cacheBytes;
      apply(record.settings);
      lastError.value = false;
      return true;
    } catch {
      settings.value = { ...settings.value, [key]: previous };
      lastError.value = true;
      return false;
    }
  }

  async function clearCache(): Promise<boolean> {
    try {
      const payload = await nativeClient.request<unknown>('app.clearCache', {});
      const record = payload as Record<string, unknown>;
      if (typeof record.cacheBytes === 'number') cacheBytes.value = record.cacheBytes;
      return true;
    } catch {
      return false;
    }
  }

  return { settings, loaded, cacheBytes, lastError, load, update, clearCache };
});
