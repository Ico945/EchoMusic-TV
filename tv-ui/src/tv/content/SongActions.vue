<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { tvFocusRegistry } from '../focus/directive';
import SongContextMenu from './SongContextMenu.vue';
import ShareDialog from '../overlays/ShareDialog.vue';
import { useTransientNotice } from './notice';
import { useTvDetailStore } from '../stores/detail';
import { useTvPlayerStore } from '../stores/player';
import type { TvTrack } from '../stores/app';

// Preserve absence: Vue otherwise casts an omitted Boolean prop to false,
// masking the confirmed favorite state loaded by the stores.
const props = withDefaults(defineProps<{ track: TvTrack | null; sourceFocusId?: string; favorite?: boolean }>(), { favorite: undefined });
const emit = defineEmits<{ close: [] }>();
const router = useRouter();
const detail = useTvDetailStore();
const player = useTvPlayerStore();
const { notice, show } = useTransientNotice();
const sharing = ref(false);
const busy = ref(false);
const error = ref('');
let generation = 0;
let alive = true;
const isFavorite = computed(() => {
  if (!props.track) return false;
  return detail.favoriteState('song', props.track.id) ?? props.favorite ?? false;
});
watch(() => props.track, () => { generation++; sharing.value = false; busy.value = false; error.value = ''; });
watch(() => props.track?.id, id => { if (id) void detail.primeFavoriteState('song'); }, { immediate: true });
onBeforeUnmount(() => { alive = false; generation++; });

async function act(name: string, track: TvTrack): Promise<void> {
  if (busy.value) return;
  if (name === 'share') { sharing.value = true; return; }
  const sourceFocusId = props.sourceFocusId;
  if (name === 'detail') {
    emit('close');
    await nextTick();
    // Closing the player menu unmounts this component; the user's navigation
    // intent must still complete after its modal Back layer is released.
    void router.push({ name: 'song-detail', params: { id: track.id }, query: { sourceFocusId } });
    return;
  }
  if (name !== 'favorite' && name !== 'play-next') return;
  const token = generation;
  const next = !isFavorite.value;
  const origin = Array.from(document.querySelectorAll<HTMLElement>('[data-focus-id]')).find(el => el.dataset.focusId === sourceFocusId);
  const siblings = origin?.parentElement ? Array.from(origin.parentElement.querySelectorAll<HTMLElement>('[data-focus-id]')) : [];
  const index = origin ? siblings.indexOf(origin) : -1;
  const neighbor = siblings[index + 1] ?? siblings[index - 1];
  busy.value = true;
  error.value = '';
  try {
    if (name === 'favorite') {
      if (!await detail.toggleFavorite('song', track.id, next)) throw new Error('favorite failed');
    } else await player.playNext(track.id);
    if (!alive || token !== generation) return;
    show(name === 'play-next' ? '已加入下一首播放' : next ? '已收藏' : '已取消收藏');
    emit('close');
    if (name === 'favorite' && !next) {
      await nextTick(); await nextTick();
      if (alive && origin && !origin.isConnected) {
        tvFocusRegistry.restore(neighbor?.isConnected ? neighbor.dataset.focusId! : 'favorites-sync');
      }
    }
  } catch {
    if (alive && token === generation) error.value = '操作未成功，请检查登录或网络后重试';
  } finally {
    if (alive && token === generation) busy.value = false;
  }
}
</script>

<template>
  <SongContextMenu :track="sharing ? null : track" :source-focus-id="sourceFocusId" :favorite="isFavorite" :favorite-known="Boolean(track && detail.favoriteState('song', track.id) !== undefined) || favorite !== undefined" :busy="busy" :error="error" @close="emit('close')" @action="act" />
  <ShareDialog v-if="sharing && track" kind="song" :id="track.id" :title="track.title" :source-focus-id="sourceFocusId" @close="emit('close')" />
  <p v-if="notice" class="song-action-notice" role="status">{{ notice }}</p>
</template>

<style scoped>
.song-action-notice { position:fixed; z-index:40; bottom:164px; left:50%; transform:translateX(-50%); padding:18px 30px; border:1px solid var(--tv-border); border-radius:18px; background:#29252d; color:var(--tv-text); font-size:24px; }
</style>
