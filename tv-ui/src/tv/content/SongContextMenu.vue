<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import TvIcon from '../components/TvIcon.vue';
import { useBackLayerRegistry } from '../input/backCoordinator';
import { tvFocusRegistry } from '../focus/directive';
import type { TvTrack } from '../stores/app';

const props = defineProps<{
  track: TvTrack | null;
  sourceFocusId?: string;
  favorite?: boolean;
  favoriteKnown?: boolean;
  busy?: boolean;
  error?: string;
}>();

const emit = defineEmits<{
  close: [];
  action: [name: 'favorite' | 'share' | 'add-to-playlist' | 'detail' | 'play-next', track: TvTrack];
}>();

const backLayers = useBackLayerRegistry();
let releaseBackLayer: (() => void) | undefined;
const confirmingRemoval = ref(false);

function close(): void {
  if (confirmingRemoval.value && !props.busy) {
    confirmingRemoval.value = false;
    void nextTick(() => tvFocusRegistry.restore('song-menu-favorite'));
    return;
  }
  emit('close');
  // Queue removal before deferred focus restoration, so modal containment
  // no longer rejects the originating row when the Back layer releases.
  releaseBackLayer?.();
  releaseBackLayer = undefined;
}

watch(
  () => props.track,
  (track) => {
    confirmingRemoval.value = false;
    releaseBackLayer?.();
    releaseBackLayer = undefined;
    if (!track) return;
    releaseBackLayer = backLayers.register(
      { kind: 'dialog', close },
      props.sourceFocusId,
    );
    void nextTick(() => {
      tvFocusRegistry.restore('song-menu-favorite');
    });
  },
  { immediate: true },
);

onBeforeUnmount(() => { releaseBackLayer?.(); releaseBackLayer = undefined; });
const actions = computed(() => [
  { id: 'favorite', label: props.favorite ? '取消收藏' : props.favoriteKnown === false ? '收藏 · 状态待同步' : '收藏', icon: 'heart' },
  { id: 'play-next', label: '下一首播放', icon: 'playNext' },
  { id: 'detail', label: '歌曲详情', icon: 'info' },
  { id: 'share', label: '分享', icon: 'share' },
] as const);

function select(action: (typeof actions.value)[number]['id']): void {
  if (!props.track || props.busy) return;
  if (action === 'favorite' && props.favorite) {
    confirmingRemoval.value = true;
    void nextTick(() => tvFocusRegistry.restore('song-menu-cancel-remove'));
    return;
  }
  emit('action', action, props.track);
}
</script>

<template>
  <div v-if="track" class="song-menu-backdrop">
    <section class="song-menu" role="dialog" aria-modal="true" :aria-label="confirmingRemoval ? '取消收藏确认' : '歌曲操作'" :aria-busy="busy">
      <div class="menu-head">
        <span>{{ track.title }}</span>
        <small>{{ track.artist }}</small>
      </div>
      <template v-if="confirmingRemoval">
        <h2>取消收藏这首歌？</h2>
        <p class="menu-message">将从你喜欢的音乐中移除，可稍后重新收藏。</p>
        <button v-tv-focus data-focus-id="song-menu-cancel-remove" type="button" @click="close"><TvIcon name="heart" active :size="26" />保留收藏</button>
        <button v-tv-focus data-focus-id="song-menu-confirm-remove" type="button" @click="!busy && track && emit('action', 'favorite', track)"><TvIcon name="trash" :size="26" />确认取消收藏</button>
      </template>
      <button v-for="action in (confirmingRemoval ? [] : actions)"
        :key="action.id"
        v-tv-focus
        type="button"
        :data-focus-id="`song-menu-${action.id}`"
        @click="select(action.id)"
      ><TvIcon :name="action.icon" :active="action.id === 'favorite' && favorite" :size="26" />{{ action.label }}</button>
      <p v-if="busy || error" class="menu-message" role="status">{{ busy ? '正在处理…' : error }}</p>
      <button v-if="!confirmingRemoval" v-tv-focus type="button" class="close" data-focus-id="song-menu-close" @click="close"><TvIcon name="back" :size="26" />返回</button>
    </section>
  </div>
</template>

<style scoped>
.song-menu-backdrop { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; background: rgba(3, 7, 12, 0.66); }
.song-menu { width: 560px; padding: 32px; border: 1px solid var(--tv-border); border-radius: 30px; background: rgba(32, 29, 35, 0.97); box-shadow: 0 30px 90px rgba(0, 0, 0, 0.5); }
.menu-head { margin-bottom: 20px; }
.menu-head span { display: block; font-size: 30px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.menu-head small { display: block; margin-top: 6px; font-size: 21px; color: var(--tv-text-secondary); }
.song-menu button { display: flex; align-items:center; gap:16px; width: 100%; min-height: 66px; margin-top: 12px; border: 1px solid transparent; border-radius: 16px; color: var(--tv-text); font-size: 24px; text-align: left; padding: 0 22px; background: rgba(255, 255, 255, 0.06); }
.song-menu button:focus { border-color: var(--tv-border); background: rgba(255, 255, 255, 0.18); }
.song-menu h2 { font-size:28px; }.menu-message { font-size:21px; line-height:1.5; color:var(--tv-text-secondary); }
.song-menu button.close { color: var(--tv-text-secondary); background: transparent; }
</style>
