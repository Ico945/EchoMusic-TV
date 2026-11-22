<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, ref, watch } from 'vue';

const props = defineProps<{
  title: string;
  subtitle: string;
  meta: string;
  primaryLabel: string;
  artwork?: string;
  hideSocialActions?: boolean;
  hidePrimary?: boolean;
  hideShare?: boolean;
  favoriteLabel?: string;
  favorite?: boolean;
  favoriteKnown?: boolean;
  primaryDownFocusId?: string;
  primaryLeftFocusId?: string;
  compact?: boolean;
  primaryDisabled?: boolean;
}>();

const artwork = computed(() => props.artwork?.trim() ?? '');
const failedArtwork = ref('');
const expanded = ref(false);
watch(() => props.title, () => { expanded.value = false; });
watch(artwork, () => { failedArtwork.value = ''; });
function onArtworkError(event: Event) {
  // A detached image may fail after navigating to another cover.
  const source = (event.currentTarget as HTMLImageElement).getAttribute('src');
  if (source === artwork.value) failedArtwork.value = source;
}

const emit = defineEmits<{
  primary: [];
  favorite: [];
  share: [];
}>();
</script>

<template>
  <header class="detail-hero" :class="{ compact }">
    <div class="hero-glow" aria-hidden="true" />
    <div class="hero-art" aria-hidden="true">
      <img v-if="artwork && failedArtwork !== artwork" :key="artwork" :src="artwork" alt="" decoding="async" @error="onArtworkError" />
      <TvIcon v-else name="music" :size="80" />
    </div>
    <div class="hero-copy">
      <span class="eyebrow">ECHOMUSIC · 详情</span>
      <h1>{{ title }}</h1>
      <p>{{ subtitle }}</p>
      <small :class="{ 'summary-clamped': compact && !expanded }">{{ meta }}</small>
      <button v-if="compact && meta.length > 80" v-tv-focus type="button" class="summary-toggle" data-focus-id="detail-summary" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起简介' : '展开简介' }}</button>
      <div class="hero-actions">
        <button v-if="!hidePrimary" v-tv-focus type="button" class="primary" data-focus-id="detail-primary" :aria-disabled="primaryDisabled" :data-focus-down="primaryDownFocusId" :data-focus-left="primaryLeftFocusId" @click="!primaryDisabled && emit('primary')"><TvIcon v-if="primaryLabel.startsWith('播放')" name="play" /> {{ primaryLabel }}</button>
        <button v-if="!hideSocialActions" v-tv-focus type="button" data-focus-id="detail-favorite" :aria-pressed="favoriteKnown === false ? undefined : favorite" @click="emit('favorite')"><TvIcon name="heart" :active="favorite" :size="24" /> {{ favoriteLabel ?? (favorite ? '已收藏 · 取消' : favoriteKnown === false ? '收藏 · 状态待同步' : '收藏') }}</button>
        <button v-if="!hideSocialActions && !hideShare" v-tv-focus type="button" data-focus-id="detail-share" @click="emit('share')"><TvIcon name="share" /> 分享</button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.detail-hero { position: relative; min-height: 300px; display: flex; align-items: center; gap: 40px; padding: 26px 40px; border-radius: var(--tv-radius-panel); overflow: hidden; background: linear-gradient(110deg, #30252f, #222226); box-shadow: 0 24px 70px rgba(0, 0, 0, 0.3); }
.hero-glow { display:none; position: absolute; width: 620px; height: 620px; right: -130px; top: -270px; border-radius: 50%; background: radial-gradient(circle, rgba(102, 181, 255, 0.5), rgba(86, 42, 154, 0.12) 55%, transparent 70%); }
.hero-art { flex: none; width: 240px; height: 240px; display: grid; place-items: center; border-radius: 26px; background: linear-gradient(145deg, #6c4d5b, #322d36); box-shadow: 0 26px 60px rgba(0, 0, 0, 0.38); }
.hero-art i { font-size: 56px; font-style: normal; }
.hero-art { overflow: hidden; }
.hero-art img { display: block; width: 100%; height: 100%; object-fit: cover; }
.hero-copy { position: relative; min-width: 0; z-index: 2; }
.eyebrow { color: var(--tv-accent); font-size: 16px; letter-spacing: 1.8px; font-weight: 700; }
.hero-copy h1 { margin: 8px 0 4px; font-size: 46px; letter-spacing: -1.6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hero-copy p { margin: 0 0 6px; color: var(--tv-text-secondary); font-size: 23px; }
.hero-copy small { color: var(--tv-text-secondary); font-size: 20px; }
.hero-actions { margin-top: 22px; display: flex; gap: 12px; }
.hero-actions button { display:inline-flex; align-items:center; justify-content:center; gap:10px; padding:0 22px; min-width: 150px; height: 62px; border: 0; border-radius: 16px; font-size: 22px; font-weight: 700; color: white; background: rgba(255, 255, 255, 0.12); }
.hero-actions button.primary { color: #07111d; background: white; }
</style>

<style scoped>
.compact { min-height:220px; padding:22px 30px; gap:28px; }
.compact .hero-art { width:180px; height:180px; border-radius:50%; }
.compact .hero-copy { flex:1; }.compact h1 { font-size:40px; }
.compact .hero-copy small { display:block; line-height:1.45; font-size:21px; }
.summary-clamped { display:-webkit-box!important; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
.summary-toggle { padding:8px 14px; margin-top:6px; border:0; border-radius:12px; color:var(--tv-text-secondary); background:#ffffff0b; font-size:21px; }
.compact .hero-actions { margin-top:12px; }.compact .hero-actions button { height:54px; }
.primary[aria-disabled=true] { opacity:.45; }
</style>
