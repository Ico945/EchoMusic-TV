<script setup lang="ts">
import TvIcon from '../components/TvIcon.vue';
import { computed, nextTick, onBeforeUnmount, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useTransientNotice } from '../content/notice';
import { recallFocus, rememberFocus } from '../content/routeMemory';
import { tvFocusRegistry } from '../focus/directive';
import { accountMembershipLabel } from '../privacy/accountDisplay';
import { useTvAppStore } from '../stores/app';
import { useTvLibraryStore } from '../stores/library';

const PAGE_KEY = 'profile';
const app = useTvAppStore();
const library = useTvLibraryStore();
const router = useRouter();
const { notice, show } = useTransientNotice();

const loggedIn = computed(() => app.session.status === 'loggedIn');
const displayName = computed(() => app.session.displayName ?? '未登录');
const membership = computed(() => accountMembershipLabel(app.session.membership));

function openLibrary(section: string): void {
  void router.push({ name: 'library', query: { section } });
}

function requestLogin(): void {
  window.dispatchEvent(new CustomEvent('echo-tv-login-requested'));
}

async function logout(): Promise<void> {
  const ok = await library.logout();
  if (ok) {
    app.session = { status: 'loggedOut' };
    window.dispatchEvent(new CustomEvent('echo-tv-logout-requested'));
    show('已退出登录');
  } else {
    show('退出失败，请重试');
  }
}

onMounted(() => {
  const remembered = recallFocus(PAGE_KEY);
  void nextTick(() => tvFocusRegistry.restore(remembered && remembered !== 'profile-logout' ? remembered : (loggedIn.value ? 'profile-library' : 'profile-login')));
});

onBeforeUnmount(() => rememberFocus(PAGE_KEY));
</script>

<template>
  <section class="profile-page" data-page="profile">
    <header class="page-header">
      <span class="eyebrow">ACCOUNT</span>
      <h1>账号</h1>
      <p>你的酷狗概念版账号。</p>
    </header>

    <div class="profile-card">
      <span class="avatar" aria-hidden="true">{{ (displayName || '?').slice(0, 1) }}</span>
      <div class="profile-copy">
        <h2>{{ displayName }}</h2>
        <p>{{ membership }}</p>
      </div>
    </div>

    <button v-if="!loggedIn" v-tv-focus type="button" class="auth-btn" data-focus-id="profile-login" @click="requestLogin"><TvIcon name="qrCode" />立即登录</button>

    <template v-if="loggedIn">
      <div class="account-actions">
        <button v-tv-focus type="button" class="auth-btn" data-focus-id="profile-library" @click="openLibrary('favoritePlaylists')"><TvIcon name="heart" />收藏的歌单<TvIcon name="chevronRight" /></button>
        <button v-tv-focus type="button" class="auth-btn" data-focus-id="profile-created" @click="openLibrary('createdPlaylists')"><TvIcon name="playlist" />创建的歌单<TvIcon name="chevronRight" /></button>
      </div>
      <button v-tv-focus type="button" class="logout-btn" data-focus-id="profile-logout" @click="logout"><TvIcon name="logout" />退出登录</button>
    </template>

    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  </section>
</template>

<style scoped>
.profile-page { padding: 4px 14px 150px; min-width: 0; }
.page-header h1 { margin: 4px 0 0; font-size: 44px; letter-spacing: -1.6px; }
.page-header p { margin: 10px 0 0; color: var(--tv-text-secondary); font-size: 17px; }
.eyebrow { color: #72b6ff; font-size: 13px; letter-spacing: 1.8px; font-weight: 700; }
.profile-card { display: flex; align-items: center; gap: 26px; margin-top: 24px; padding: 26px 30px; border: 1px solid var(--tv-border); border-radius: var(--tv-radius-panel); background: linear-gradient(105deg, #112d58 0%, #123b75 42%, #18152d 100%); }
.avatar { width: 96px; height: 96px; flex: none; display: grid; place-items: center; border-radius: 50%; font-size: 40px; color: white; background: linear-gradient(145deg, #2499ff, #7845c0 58%, #ff607e); }
.profile-copy h2 { margin: 0; font-size: 32px; }
.profile-copy p { margin: 6px 0 0; color: var(--tv-text-secondary); font-size: 16px; }
.auth-btn { min-width: 180px; height: 52px; margin-top: 20px; border: 0; border-radius: 26px; color: #07111d; font-size: 16px; font-weight: 700; background: white; }
.account-actions { display:flex; gap:24px; margin:32px 0; }.account-actions .auth-btn { min-width:260px; height:72px; border-radius:18px; font-size:24px; }
.logout-btn { min-height:60px; padding:0 24px; border:1px solid var(--tv-border); border-radius:16px; color:var(--tv-text-secondary); background:transparent; font-size:22px; }
.playlist-section { margin-top: 26px; }
.playlist-section h3 { margin: 0 0 13px; font-size: 22px; }
.empty { padding: 14px 8px; color: var(--tv-text-secondary); font-size: 15px; }
.notice { position: fixed; left: 50%; bottom: 40px; z-index: 20; padding: 12px 24px; border-radius: 18px; transform: translateX(-50%); color: #fff; font-size: 15px; background: rgba(20, 90, 160, 0.95); }
.auth-btn,.logout-btn { display:inline-flex; align-items:center; justify-content:center; gap:10px; padding:0 20px; }
</style>
