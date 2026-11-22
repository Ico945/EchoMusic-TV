<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, nextTick, onBeforeUnmount, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { recallFocus, rememberFocus } from '../content/routeMemory';
import { useInitialFocusRestore } from '../content/initialFocus';
import { useTvCapabilitiesStore } from '../stores/capabilities';

const PAGE_KEY = 'recognize';
const restoreInitialFocus = useInitialFocusRestore('nav-more');
const capabilities = useTvCapabilitiesStore();
const router = useRouter();
let releaseRecognition: (() => void) | undefined;

const microphone = computed(() => capabilities.capabilities.microphone);
const recognition = computed(() => capabilities.recognitionState);
const matches = computed(() => capabilities.matches);

const canStart = computed(() => microphone.value === 'available' && ['idle', 'finished', 'error'].includes(recognition.value));

function openSearch(): void {
  void router.push({ name: 'search' });
}

onMounted(async () => {
  releaseRecognition = capabilities.connect();
  if (!capabilities.loaded) await capabilities.load();
  void nextTick(() => {
    restoreInitialFocus(recallFocus(PAGE_KEY) ?? (canStart.value ? 'recognize-start' : 'recognize-search'));
  });
});

onBeforeUnmount(() => {
  rememberFocus(PAGE_KEY);
  releaseRecognition?.();
});
</script>

<template>
  <section class="recognize-page" data-page="recognize">
    <header class="page-header">
      <span class="eyebrow">RECOGNIZE</span>
      <h1>听歌识曲</h1>
      <p>对着电视说出或播放一段音乐，帮你找到这首歌。</p>
    </header>

    <div v-if="microphone === 'unavailable'" class="degrade">
      <p>当前设备没有可用麦克风，无法使用听歌识曲。</p>
      <button v-tv-focus type="button" data-action="open-search" data-focus-id="recognize-search" @click="openSearch">改用搜索找歌</button>
    </div>

    <div v-else-if="microphone === 'permission-required'" class="degrade">
      <p>需要麦克风权限才能使用听歌识曲。</p>
      <button v-tv-focus type="button" data-action="start-recognition" data-focus-id="recognize-start" @click="capabilities.startRecognition()">开始识别</button>
      <button v-tv-focus type="button" data-action="open-search" data-focus-id="recognize-search" @click="openSearch">改用搜索找歌</button>
    </div>

    <template v-else>
      <div class="recognize-stage">
        <span class="pulse" :class="{ active: recognition === 'recording' || recognition === 'processing' }" aria-hidden="true"><TvIcon name="mic" :size="56" /></span>
        <p v-if="recognition === 'recording'">正在聆听，最多 10 秒…</p>
        <p v-else-if="recognition === 'processing'">正在识别…</p>
        <p v-else-if="recognition === 'finished' && matches.length">找到 {{ matches.length }} 个结果</p>
        <p v-else-if="recognition === 'finished'">没有找到匹配的歌曲</p>
        <p v-else-if="recognition === 'error'">{{ capabilities.recognitionMessage || '识别失败，请重试' }}</p>
        <p v-else>准备好后开始</p>
      </div>

      <div v-if="matches.length" class="matches">
        <div v-for="match in matches" :key="match.id" class="match-row">
          <strong>{{ match.title }}</strong><small>{{ match.artist }} · 置信度 {{ Math.round(match.confidence * 100) }}%</small>
        </div>
      </div>

      <div class="actions">
        <button
          v-if="canStart"
          v-tv-focus
          type="button"
          data-action="start-recognition"
          data-focus-id="recognize-start"
          @click="capabilities.startRecognition()"
        >开始识别</button>
        <button
          v-else-if="recognition === 'recording' || recognition === 'processing'"
          v-tv-focus
          type="button"
          data-focus-id="recognize-cancel"
          @click="capabilities.cancelRecognition()"
        >取消</button>
        <button v-tv-focus type="button" data-action="open-search" data-focus-id="recognize-search" @click="openSearch">改用搜索找歌</button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.recognize-page { padding: 4px 14px 150px; min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size: 44px; letter-spacing: -1.6px; }
.page-header p { margin: 10px 0 0; color: var(--tv-text-secondary); font-size: 17px; }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.degrade { margin-top: 26px; padding: 26px 30px; border-radius: var(--tv-radius-panel); background: rgba(255, 255, 255, 0.04); }
.degrade p { margin: 0 0 16px; color: var(--tv-text-secondary); font-size: 16px; }
.degrade button, .actions button { min-width: 200px; height: 52px; margin-right: 12px; border: 1px solid rgba(124, 188, 255, 0.35); border-radius: 26px; color: #dceeff; font-size: 16px; background: rgba(36, 102, 164, 0.3); }
.recognize-stage { margin-top: 26px; display: grid; place-items: center; gap: 14px; min-height: 260px; border-radius: var(--tv-radius-panel); background: linear-gradient(105deg, #112d58 0%, #123b75 42%, #18152d 100%); }
.recognize-stage .pulse { width: 110px; height: 110px; display: grid; place-items: center; border-radius: 50%; font-size: 40px; color: white; background: linear-gradient(145deg, #2499ff, #7845c0); }
.recognize-stage .pulse.active { animation: pulse 1.2s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { transform: scale(1); } 50% { transform: scale(1.12); } }
.recognize-stage p { margin: 0; color: #d8e6f7; font-size: 18px; }
.matches { display: flex; flex-direction: column; gap: 10px; margin-top: 20px; }
.match-row { display: flex; align-items: baseline; gap: 14px; padding: 14px 18px; border-radius: var(--tv-radius-card); background: rgba(255, 255, 255, 0.03); }
.match-row strong { font-size: 16px; }
.match-row small { color: var(--tv-text-secondary); font-size: 14px; }
.actions { margin-top: 22px; }
</style>
