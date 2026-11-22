import { ref } from 'vue';
import { defineStore } from 'pinia';
import { nativeClient } from '../native/client';
import { isNativeBridgeAvailable } from '../native/transport';

export type MicrophoneState = 'available' | 'permission-required' | 'unavailable';

export type TvCapabilities = {
  microphone: MicrophoneState;
  voiceInput: boolean;
  blur: boolean;
};

export type RecognitionState = 'idle' | 'recording' | 'processing' | 'finished' | 'error';

export type RecognitionMatch = { id: string; title: string; artist: string; confidence: number };

const microphoneStates = ['available', 'permission-required', 'unavailable'] as const;
const recognitionStates = ['idle', 'recording', 'processing', 'finished', 'error'] as const;

function parseCapabilities(value: unknown): TvCapabilities {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('capabilities must be an object');
  const record = value as Record<string, unknown>;
  const microphone = record.microphone;
  if (typeof microphone !== 'string' || !(microphoneStates as readonly string[]).includes(microphone)) {
    throw new Error('microphone state is unsupported');
  }
  if (typeof record.voiceInput !== 'boolean' || typeof record.blur !== 'boolean') {
    throw new Error('capability flags must be booleans');
  }
  return { microphone: microphone as MicrophoneState, voiceInput: record.voiceInput, blur: record.blur };
}

function parseRecognitionEvent(value: unknown): { state: RecognitionState; matches: RecognitionMatch[]; message: string } {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('recognition event must be an object');
  const record = value as Record<string, unknown>;
  const state = record.state;
  if (typeof state !== 'string' || !(recognitionStates as readonly string[]).includes(state)) {
    throw new Error('recognition state is unsupported');
  }
  const rawMatches = record.matches;
  const matches: RecognitionMatch[] = [];
  if (rawMatches !== undefined && rawMatches !== null) {
    if (!Array.isArray(rawMatches)) throw new Error('recognition matches must be an array');
    for (const item of rawMatches) {
      if (typeof item !== 'object' || item === null || Array.isArray(item)) throw new Error('recognition match is invalid');
      const match = item as Record<string, unknown>;
      if (
        typeof match.id !== 'string' ||
        typeof match.title !== 'string' ||
        typeof match.artist !== 'string' ||
        typeof match.confidence !== 'number'
      ) {
        throw new Error('recognition match is invalid');
      }
      matches.push({ id: match.id, title: match.title, artist: match.artist, confidence: match.confidence });
    }
  }
  return { state: state as RecognitionState, matches, message: typeof record.message === 'string' ? record.message : '' };
}

export const useTvCapabilitiesStore = defineStore('tv-capabilities', () => {
  const capabilities = ref<TvCapabilities>({ microphone: 'available', voiceInput: false, blur: false });
  const loaded = ref(false);
  const recognitionState = ref<RecognitionState>('idle');
  const matches = ref<RecognitionMatch[]>([]);
  const recognitionMessage = ref('');

  function applyRecognitionEvent(value: unknown): void {
    try {
      const event = parseRecognitionEvent(value);
      recognitionState.value = event.state;
      matches.value = event.matches;
      recognitionMessage.value = event.message;
    } catch {
      // Keep the last valid projection on malformed events.
    }
  }

  async function load(): Promise<void> {
    // The native client queues requests until Android attaches the bridge.
    // Do not permanently assume support during that startup window.
    try {
      capabilities.value = parseCapabilities(await nativeClient.request<unknown>('capability.get', {}));
      loaded.value = true;
    } catch {
      // Retain the last verified projection; unknown visuals stay no-blur.
    }
  }

  async function startRecognition(): Promise<void> {
    recognitionState.value = 'recording';
    matches.value = [];
    recognitionMessage.value = '';
    try {
      const payload = await nativeClient.request<unknown>('recognition.start', {});
      applyRecognitionEvent(payload);
    } catch {
      recognitionState.value = 'error';
      recognitionMessage.value = '识别启动失败，请重试';
    }
  }

  async function cancelRecognition(): Promise<void> {
    try {
      const payload = await nativeClient.request<unknown>('recognition.cancel', {});
      applyRecognitionEvent(payload);
    } catch {
      recognitionState.value = 'idle';
    }
  }

  function connect(): () => void {
    if (!isNativeBridgeAvailable()) return () => undefined;
    return nativeClient.on('recognition.stateChanged', applyRecognitionEvent);
  }

  return {
    capabilities,
    loaded,
    recognitionState,
    matches,
    recognitionMessage,
    load,
    startRecognition,
    cancelRecognition,
    connect,
  };
});
