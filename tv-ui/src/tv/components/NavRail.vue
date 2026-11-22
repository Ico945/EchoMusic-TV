<script setup lang="ts">
import TvIcon from './TvIcon.vue';
import { useRoute } from 'vue-router';
import { computed } from 'vue';
import { TV_NAV_ITEMS } from '../router';
import { navigationSection, TOP_NAV_ORDER } from '../router/navigation';

defineProps<{ accountLabel?: string; hasTrack?: boolean }>();
const emit = defineEmits<{ enterContent: [destination: string, activate?: boolean]; openPlayer: []; account: [] }>();
const route = useRoute();
const items = TOP_NAV_ORDER.map(id => TV_NAV_ITEMS.find(item => item.id === id)!);
const activeSection = computed(() => navigationSection(route));
</script>

<template>
  <nav class="top-nav" data-top-nav aria-label="主导航">
    <div class="brand" aria-label="EchoMusic"><TvIcon name="music" :size="30" /><b>EchoMusic</b></div>
    <div class="nav-tabs">
      <button v-for="(item,index) in items" :key="item.id" v-tv-focus type="button" class="nav-item"
        :class="{ active: activeSection === item.id }" :aria-current="activeSection === item.id ? 'page' : undefined"
        :data-nav-id="item.id" :data-focus-id="`nav-${item.id}`" data-focus-group="navigation"
        :data-focus-left="index ? `nav-${items[index-1].id}` : `nav-${item.id}`"
        :data-focus-right="index === items.length-1 ? 'nav-playing' : `nav-${items[index+1].id}`"
        :data-focus-up="`nav-${item.id}`"
        @click="emit('enterContent', item.id, true)">
        <span class="nav-glyph" aria-hidden="true"><TvIcon :name="item.icon" :size="26" /></span>
        <span>{{ item.label }}</span>
      </button>
    </div>
    <button v-tv-focus type="button" class="now-entry" data-focus-id="nav-playing" data-focus-group="navigation"
      data-focus-left="nav-more" data-focus-right="account" data-focus-up="nav-playing"
      :aria-label="hasTrack ? '正在播放，打开歌词' : '正在播放'"
      @click="emit('openPlayer')">
      <TvIcon name="waveform" :size="26" /><span>正在播放</span><i v-if="hasTrack" />
    </button>
    <button v-tv-focus type="button" class="account-entry" data-focus-id="account" data-focus-group="navigation"
      data-focus-left="nav-playing" data-focus-right="account" data-focus-up="account"
      :aria-label="accountLabel ?? '扫码登录'" :title="accountLabel ?? '扫码登录'"
      @click="emit('account')">
      <TvIcon name="user" :size="26" /><span>{{ accountLabel ? '账户' : '登录' }}</span>
    </button>
  </nav>
</template>

<style scoped>
.top-nav { min-width:0; height:88px; display:flex; align-items:center; gap:16px; padding:4px 14px 14px; border-bottom:1px solid #ffffff13; position:relative; z-index:10; }
/* One persistent chrome layer bounds focus-animation repaint. ABBA device
   profiling verifies this; do not promote every card in a long list. */
.top-nav { transform:translateZ(0); will-change:transform; }
.brand { display:flex; align-items:center; gap:10px; flex:none; margin-right:12px; font-size:25px; letter-spacing:-1px; }.brand .tv-icon { color:var(--tv-accent); }.brand b { font-weight:700; }
.nav-tabs { display:flex; gap:6px; flex:1; min-width:0; }
button { --tv-focus-scale:1.035; height:60px; padding:0 17px; display:flex; align-items:center; justify-content:center; gap:9px; white-space:nowrap; border:0; border-radius:18px; font-size:23px; color:var(--tv-text-secondary); background:transparent; }
.nav-item.active { color:#fff; background:#ffffff10; }.nav-glyph { display:flex; }
button:focus { color:#211b23; background:#f5f1f5; }.now-entry { background:#ffffff09; position:relative; }.now-entry i { width:6px; height:6px; border-radius:50%; background:var(--tv-accent); }.account-entry { padding:0 13px; font-size:21px; }
</style>
