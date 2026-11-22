<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import type { PlayerTrack } from '../stores/player';
import { computed, nextTick, onMounted, ref, useTemplateRef, watch } from 'vue';
import { useTvPlayerStore } from '../stores/player';
import { tvFocusRegistry } from '../focus/directive';
import { scrollWithin } from '../content/scrollVisibility';

const props = defineProps<{ queue: PlayerTrack[]; queueIndex: number; selecting?: boolean }>();
const player = useTvPlayerStore();
const selectedId = ref(props.queue[props.queueIndex]?.id ?? props.queue[0]?.id ?? '');
const selected = computed(() => props.queue.find(track => track.id === selectedId.value));
const editing = ref(false);
const busy = computed(() => editing.value || props.selecting);
const currentId = computed(() => props.queue[props.queueIndex]?.id);
const pendingCount = computed(() => Math.max(0, props.queue.length - props.queueIndex - 1));
const editable = computed(() => Boolean(selected.value && selected.value.id !== currentId.value));
const selectedFocus = computed(() => selected.value ? `queue-track-${selected.value.id}` : 'queue-close');
const message = ref('');
const clearArmed = ref(false);
async function edit(action: 'shuffle' | 'remove' | 'clearPending' | 'next') {
  if (busy.value) return;
  if ((action === 'shuffle' && pendingCount.value < 2) || (action === 'clearPending' && !pendingCount.value)) return;
  if (action === 'clearPending' && !clearArmed.value) { clearArmed.value = true; message.value = '再次确认清空待播，当前歌曲与已播历史保留'; return; }
  if ((action === 'remove' || action === 'next') && (!selected.value || selected.value.id === props.queue[props.queueIndex]?.id)) { message.value = '请先选择另一首歌曲'; return; }
  editing.value = true; message.value = ''; clearArmed.value = false;
  const removedIndex = props.queue.findIndex(track => track.id === selectedId.value);
  try {
    if (action === 'next') await player.playNext(selectedId.value);
    else await player.editQueue(action, action === 'remove' ? selectedId.value : undefined);
    message.value = action === 'shuffle' ? '待播顺序已随机排列' : action === 'remove' ? '已从队列移除' : action === 'next' ? '已调整为下一首播放' : '已清空待播';
    if (action === 'remove') {
      await nextTick();
      selectedId.value = props.queue[Math.min(removedIndex, props.queue.length - 1)]?.id ?? '';
      tvFocusRegistry.restore(selectedId.value ? `queue-track-${selectedId.value}` : 'queue-close');
    }
  } catch { message.value = '队列修改失败，原状态已保留，请重试'; }
  finally { editing.value = false; }
}
const list = useTemplateRef<HTMLOListElement>('list');
function jumpToCurrent() {
  if (!currentId.value) return;
  selectedId.value = currentId.value;
  tvFocusRegistry.restore(`queue-track-${currentId.value}`);
  const row = list.value?.querySelector<HTMLElement>('[aria-current="true"]');
  if (row && list.value) scrollWithin(row, list.value, { block: 'center', behavior: 'auto' });
}
function selectTrack(id: string) { if (!busy.value) emit('select', id); }
watch([() => props.queue.map(track => track.id).join('|'), () => props.queueIndex], async () => {
  const focused = (document.activeElement as HTMLElement | null)?.dataset.focusId ?? '';
  if (!selected.value) selectedId.value = currentId.value ?? props.queue[0]?.id ?? '';
  clearArmed.value = false;
  await nextTick();
  if ((focused.startsWith('queue-track-') && !props.queue.some(track => focused === `queue-track-${track.id}`))
    || (!editable.value && ['queue-next', 'queue-remove'].includes(focused))
    || (focused === 'queue-clear' && !pendingCount.value) || (focused === 'queue-shuffle' && pendingCount.value < 2)) {
    tvFocusRegistry.restore(selectedFocus.value);
  }
});
onMounted(() => { void nextTick(() => {
  const current = list.value?.querySelector<HTMLElement>('[aria-current="true"]');
  if (current && list.value) scrollWithin(current, list.value, { block: 'center', behavior: 'auto' });
}); });
const emit = defineEmits<{ close: []; select: [trackId: string] }>();
</script>

<template>
  <aside class="player-drawer" role="dialog" aria-modal="true" aria-label="播放队列">
    <header><h2>播放队列</h2><button v-tv-focus type="button" data-focus-id="queue-close" @click="emit('close')"><TvIcon name="close" />关闭</button></header>
    <p class="queue-summary">共 {{ queue.length }} 首 · 待播 {{ pendingCount }} 首</p>
    <div class="queue-tools">
      <button v-tv-focus type="button" data-focus-id="queue-current" data-focus-up="queue-close" :data-focus-down="selectedFocus" :aria-disabled="!currentId" @click="jumpToCurrent"><TvIcon name="waveform" />回到当前</button>
      <button v-tv-focus type="button" data-focus-id="queue-shuffle" data-focus-up="queue-close" :data-focus-down="selectedFocus" :aria-busy="busy" :aria-disabled="pendingCount < 2" @click="edit('shuffle')"><TvIcon name="shuffle" />随机待播</button>
      <button v-tv-focus type="button" data-focus-id="queue-clear" data-focus-up="queue-close" :data-focus-down="selectedFocus" :aria-busy="busy" :aria-disabled="!pendingCount" @blur="clearArmed = false" @click="edit('clearPending')">{{ clearArmed ? '确认清空' : '清空待播' }}</button>
    </div>
    <ol ref="list">
      <li v-for="(track, index) in queue" :key="track.id">
        <button
          v-tv-focus
          type="button"
          :data-focus-id="`queue-track-${track.id}`"
          :aria-current="index === queueIndex ? 'true' : undefined"
          :data-focus-up="index ? `queue-track-${queue[index - 1].id}` : 'queue-current'"
          :data-focus-down="index + 1 < queue.length ? `queue-track-${queue[index + 1].id}` : index !== queueIndex ? 'queue-next' : 'queue-close'"
          data-focus-left="queue-current"
          :data-focus-right="index !== queueIndex ? 'queue-next' : 'queue-close'"
          :aria-busy="selecting"
          @focus="selectedId = track.id"
          @click="selectTrack(track.id)"
        >
          <span class="queue-position"><TvIcon v-if="index === queueIndex" name="waveform" :size="28" /><small v-if="index === queueIndex">{{ player.error ? '播放失败' : player.isPlaying ? '正在播放' : '当前曲目' }}</small><template v-else>{{ index + 1 }}</template></span><strong>{{ track.title }}</strong><small>{{ track.artists.join(' · ') }}</small>
        </button>
      </li>
    </ol>
    <footer class="queue-item-tools">
      <p>{{ selected?.title || '队列为空' }}</p>
      <p class="queue-help">{{ editable ? '确认播放 · 右键管理 · 左键回到当前' : '上下浏览歌曲 · 确认播放' }}</p>
      <div class="queue-tools">
        <button v-tv-focus type="button" data-focus-id="queue-next" :data-focus-left="selectedFocus" data-focus-right="queue-remove" :data-focus-up="selectedFocus" :aria-busy="busy" :aria-disabled="!editable" @click="edit('next')"><TvIcon name="playNext" />下一首播放</button>
        <button v-tv-focus type="button" data-focus-id="queue-remove" data-focus-left="queue-next" :data-focus-up="selectedFocus" :aria-busy="busy" :aria-disabled="!editable" @click="edit('remove')"><TvIcon name="close" />移除</button>
      </div>
      <p v-if="busy || message || player.commandFailure" role="status">{{ busy ? '正在处理…' : message || player.commandFailure }}</p>
    </footer>
  </aside>
</template>

<style scoped>
.player-drawer { display:flex; flex-direction:column; width:640px; max-width:100%; max-height:864px; overflow:hidden; padding:32px; border:1px solid rgba(255,255,255,.18); border-radius:30px; color:#fff; background:rgba(17,27,42,.9); -webkit-backdrop-filter:blur(28px) saturate(130%); backdrop-filter:blur(28px) saturate(130%); box-shadow:0 36px 100px rgba(0,0,0,.58),inset 0 1px rgba(255,255,255,.1); }
header { flex:none; display:flex; align-items:center; justify-content:space-between; gap:20px; } h2 { margin:0; font-size:32px; } header button { min-width:104px; height:50px; border:0; border-radius:25px; color:#fff; background:rgba(255,255,255,.12); font-size:22px; } ol { flex:1; min-height:0; overflow:auto; padding:12px; margin:20px -12px 0; list-style:none; } li+li { margin-top:10px; } li button { --tv-focus-scale:1; width:100%; min-height:92px; display:grid; grid-template-columns:96px minmax(0,1fr); align-items:center; gap:3px 14px; padding:12px 16px; border:0; border-radius:18px; color:#fff; text-align:left; background:rgba(255,255,255,.06); } li button[aria-current="true"] { background:rgba(244,121,147,.22); } li span { grid-row:span 2; color:var(--tv-accent); font-size:14px; } li strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:24px; } li small { color:rgba(255,255,255,.6); font-size:16px; }
header button { display:inline-flex; align-items:center; justify-content:center; gap:8px; padding:0 16px; min-height:66px; }
.queue-position { display:flex; align-items:center; justify-content:center; flex-direction:column; gap:6px; }
.queue-position small { font-size:16px; color:inherit; }
li button:focus .queue-position,li button:focus small { color:inherit; }
</style>

<style scoped>
.queue-tools { display:flex; gap:14px; margin-top:14px; flex:none; }
.queue-tools button { display:inline-flex; align-items:center; gap:10px; min-height:56px; border:0; padding:10px 16px; border-radius:14px; background:#ffffff12; color:var(--tv-text); font-size:21px; }
.queue-item-tools { flex:none; padding-top:14px; border-top:1px solid #ffffff15; }
.queue-item-tools p { margin:8px 0 0; font-size:20px; color:var(--tv-text-secondary); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.queue-summary { margin:12px 0 0; font-size:22px; color:var(--tv-text-secondary); }
.queue-tools button { min-height:62px; padding:10px 12px; }
.queue-tools button[aria-disabled=true] { opacity:.38; }
.queue-item-tools .queue-help { font-size:19px; }
</style>
