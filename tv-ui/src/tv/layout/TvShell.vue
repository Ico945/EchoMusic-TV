<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref, useTemplateRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import TopNavigation from '../components/NavRail.vue';
import { navigationSection } from '../router/navigation';
import MiniPlayer from '../components/MiniPlayer.vue';
import TvOverlayTransition from '../components/TvOverlayTransition.vue';
import LoginQrDialog from '../overlays/LoginQrDialog.vue';
import { contentScrollOwner, scrollWithin } from '../content/scrollVisibility';
import { cancelScrollMotion } from '../input/scrollMotion';
import { TOP_NAV_ORDER } from '../router/navigation';
import { tvFocusRegistry } from '../focus/directive';
import {
  BACK_LAYER_REGISTRY_KEY,
  BackLayerRegistry,
  type BackLayer,
} from '../input/backCoordinator';
import { isTextEditingTarget, normalizeRemoteKey, REMOTE_DIRECTIONS, remoteEventTarget } from '../input/remoteKeys';
import { PLAYER_MEDIA_KEYS } from '../player/playerKeys';
import { nativeClient } from '../native/client';
import { TV_NAV_ITEMS } from '../router';
import { useTvAppStore } from '../stores/app';
import { useTvAuthStore } from '../stores/auth';
import { useTvPlayerStore } from '../stores/player';

const player = useTvPlayerStore();
const app = useTvAppStore();
const auth = useTvAuthStore();
const releasePlayerProjection = player.connect();
const releaseAuthProjection = auth.connect();
const router = useRouter();
const route = useRoute();
const exitDialogOpen = ref(false);
const loginOpen = ref(false);
const loginSourceFocusId = ref<string | undefined>(undefined);
const priorFocusId = ref<string | null>(null);
const lastNonPlayerFocusId = ref(`nav-${String(route.name ?? 'home')}`);
const shell = useTemplateRef<HTMLElement>('shell');
const contentSurface = useTemplateRef<HTMLElement>('contentSurface');
const lastBackLayer = ref('none');
const backDispatchCount = ref(0);
let focusObserver: MutationObserver | undefined;
let focusRepairScheduled = false;
let sessionRestoreActive = true;
let releaseExitLayer: (() => void) | undefined;
type PlaybackOrigin = { path: string; focusId: string; scrollTop: number };
let playbackOrigin: PlaybackOrigin | undefined;
let pendingPlaybackReturn: PlaybackOrigin | undefined;
let playIntent = 0;
let lastContentFocusId: string | undefined;
let dockOrigin: (PlaybackOrigin & {
  element: HTMLElement;
  targets: { element: HTMLElement; id: string | undefined }[];
  scrollHeight: number;
}) | undefined;
let browseOrigin: PlaybackOrigin | undefined;
let navigationIntent = 0;
const routeOrigins = new Map<string, PlaybackOrigin>();
let pendingRouteReturn: PlaybackOrigin | undefined;
const routeDirection = ref(1);
function restoreRouteOrigin(): boolean {
  const origin = pendingRouteReturn;
  if (!origin || origin.path !== route.fullPath || loginOpen.value || exitDialogOpen.value) return false;
  const target = Array.from(contentSurface.value?.querySelectorAll<HTMLElement>('[data-focus-id]') ?? [])
    .find(el => el.dataset.focusId === origin.focusId && !el.hasAttribute('disabled') && !el.closest('[inert]'));
  if (!target) return false;
  pendingRouteReturn = undefined;
  tvFocusRegistry.register(target); tvFocusRegistry.restore(origin.focusId);
  if (contentSurface.value) contentSurface.value.scrollTop = origin.scrollTop;
  return true;
}
const releaseRouteGuard = router.beforeEach((to, from) => {
  cancelScrollMotion(undefined, undefined, true);
  const returning = routeOrigins.get(from.fullPath);
  const previousTab = TOP_NAV_ORDER.findIndex(id => id === navigationSection(from));
  const nextTab = TOP_NAV_ORDER.findIndex(id => id === navigationSection(to));
  routeDirection.value = returning?.path === to.fullPath || (from.name === 'now-playing' && playbackOrigin?.path === to.fullPath)
    ? -1 : previousTab !== nextTab && nextTab < previousTab ? -1 : 1;
  if (returning?.path === to.fullPath) pendingRouteReturn = returning;
  else {
    pendingRouteReturn = undefined;
    const active = document.activeElement as HTMLElement | null;
    if (from.name && active?.closest('[data-page]') && active.dataset.focusId) {
      routeOrigins.set(to.fullPath, { path: from.fullPath, focusId: active.dataset.focusId, scrollTop: contentSurface.value?.scrollTop ?? 0 });
      if (routeOrigins.size > 80) routeOrigins.delete(routeOrigins.keys().next().value!);
    }
  }
  if (to.name === 'now-playing' && from.name !== 'now-playing') {
    const active = document.activeElement as HTMLElement | null;
    const fromChrome = Boolean(active?.closest('[data-mini-player]') || active?.dataset.focusId === 'nav-playing');
    const remembered = active?.closest('[data-mini-player]') ? dockOrigin ?? browseOrigin : browseOrigin;
    playbackOrigin = fromChrome && remembered?.path === from.fullPath
      ? { ...remembered, scrollTop: contentSurface.value?.scrollTop ?? remembered.scrollTop }
      : { path: from.fullPath, focusId: active?.dataset.focusId ?? lastNonPlayerFocusId.value, scrollTop: contentSurface.value?.scrollTop ?? 0 };
  }
});
const releasePlayIntent = player.$onAction(({ name, after }) => {
  if (name !== 'playQueue' || route.name === 'now-playing' || route.name === 'mv-detail') return;
  const source = route.fullPath;
  const intent = ++playIntent;
  after(() => {
    // Do not pull the user back if they left the source while resolving audio.
    if (intent === playIntent && route.fullPath === source) void router.push({ name: 'now-playing' });
  });
});

function restorePlaybackOrigin(): boolean {
  const origin = pendingPlaybackReturn;
  if (!origin || route.fullPath !== origin.path || loginOpen.value || exitDialogOpen.value) return false;
  const target = Array.from(shell.value?.querySelectorAll<HTMLElement>('[data-focus-id]') ?? [])
    .find(el => el.dataset.focusId === origin.focusId && !el.hasAttribute('disabled') && !el.closest('[inert]'));
  if (!target) return false;
  pendingPlaybackReturn = undefined;
  tvFocusRegistry.register(target);
  tvFocusRegistry.restore(origin.focusId);
  if (contentSurface.value) contentSurface.value.scrollTop = origin.scrollTop;
  return true;
}
const backLayers = new BackLayerRegistry((id) => void nextTick(() => {
  // A route's source card may still be loading. restore() falls back to the
  // first registered target when absent, which steals the new page's focus.
  // Let its loading/default target own the hand-off until that card exists.
  const target = shell.value && focusTargets(shell.value).find(node => node.dataset.focusId === id);
  if (target) tvFocusRegistry.restore(id);
  else scheduleDetachedFocusRepair();
}));
provide(BACK_LAYER_REGISTRY_KEY, backLayers);

const accountLabel = computed(() => (app.session.status === 'loggedIn' ? app.session.displayName : undefined));
const primaryRouteNames = new Set<string>(TV_NAV_ITEMS.map((item) => item.id));
const primaryNavId = computed(() => `nav-${navigationSection(route)}`);
const blockingLayers = ['ime', 'dialog', 'drawer'] as const;
const parentRouteByName: Record<string, string> = {
  'daily-recommendations': 'home',
  'ranking-detail': 'ranking',
  'playlist-detail': 'home',
  'album-detail': 'home',
  'artist-detail': 'home',
  'song-detail': 'home',
  'mv-detail': 'home',
  'now-playing': 'home',
  favorites: 'library',
  history: 'library',
  cloud: 'library',
  purchased: 'library',
  profile: 'home',
  'share-resolve': 'more',
  settings: 'more',
  recognize: 'more',
  plugin: 'more',
  about: 'more',
};

function leaveCurrentPage(): void {
  const name = String(route.name ?? '');
  if (primaryRouteNames.has(name)) {
    if (!(document.activeElement as HTMLElement | null)?.closest('[data-top-nav]')) {
      tvFocusRegistry.restore(primaryNavId.value);
    } else if (name === 'home') openExitDialog();
    else void router.replace({ name: 'home' }).then(() => restoreAfterRender('nav-home'));
    return;
  }
  if (name === 'now-playing' && playbackOrigin) {
    if (router.options.history.state.back === playbackOrigin.path) router.back();
    else void router.replace(playbackOrigin.path);
    return;
  }
  if (router.options.history.state.back) {
    router.back();
    return;
  }
  void router.replace({ name: parentRouteByName[name] ?? 'home' });
}

const fallbackLayers = computed<BackLayer[]>(() => {
  if (route.name !== 'home') return [{ kind: 'page', close: leaveCurrentPage }];
  return [{ kind: 'home-exit', close: leaveCurrentPage }];
});
const mvActive = computed(() => route.name === 'mv-detail');
const playerActive = computed(() => route.name === 'now-playing');

function focusPageContent(): void {
  const page = contentSurface.value?.querySelector<HTMLElement>('[data-page]');
  if (!page) return;
  const targets = Array.from(page.querySelectorAll<HTMLElement>('[data-focus-id]'))
    .filter(el => !el.hasAttribute('disabled') && el.getAttribute('aria-disabled') !== 'true' && !el.closest('[inert]'));
  targets.forEach(el => tvFocusRegistry.register(el));
  const target = targets.find(el => el.dataset.focusId === page.dataset.lastContentFocus)
    ?? targets.find(el => el.dataset.focusId === page.dataset.defaultFocusId)
    ?? targets.find(el => el.getAttribute('aria-pressed') === 'true')
    ?? targets[0];
  if (target?.dataset.focusId) tvFocusRegistry.restore(target.dataset.focusId);
}

async function enterNavigationContent(destination: string, activate = false): Promise<void> {
  const intent = ++navigationIntent;
  const origin = (document.activeElement as HTMLElement | null)?.dataset.focusId;
  if (navigationSection(route) !== destination || (activate && route.name !== destination)) await router.push({ name: destination });
  await nextTick();
  if (intent !== navigationIntent || !sessionRestoreActive || navigationSection(route) !== destination) return;
  const active = document.activeElement as HTMLElement | null;
  if (active?.closest('[data-top-nav]') && active.dataset.focusId !== origin) return;
  if (active?.closest('[data-mini-player],[role=dialog]') || backLayers.hasAny(blockingLayers)) return;
  focusPageContent();
}

function focusPlaybackDock(): void {
  if (!player.currentTrack || playerActive.value || mvActive.value || backLayers.hasAny(blockingLayers)) return;
  cancelScrollMotion(undefined, undefined, true);
  const active = document.activeElement as HTMLElement | null;
  if (!active?.closest('[data-mini-player]')) {
    dockOrigin = active && contentSurface.value ? {
      path: route.fullPath, focusId: active.dataset.focusId ?? primaryNavId.value,
      scrollTop: contentSurface.value.scrollTop, element: active,
      targets: focusTargets(contentSurface.value).map(element => ({ element, id: element.dataset.focusId })),
      scrollHeight: contentSurface.value.scrollHeight,
    } : undefined;
  }
  tvFocusRegistry.restore('mini-open');
}

function returnFromDock(): void {
  const surface = contentSurface.value;
  const page = surface?.querySelector<HTMLElement>('[data-page]');
  if (!surface || !page || backLayers.hasAny(blockingLayers)) return;
  const targets = focusTargets(page).filter(element => {
    if (element.matches(':disabled,[disabled],[aria-disabled="true"]')) return false;
    for (let node: HTMLElement | null = element; node; node = node.parentElement) {
      if (node.hidden || node.hasAttribute('inert') || node.getAttribute('aria-hidden') === 'true') return false;
      const style = getComputedStyle(node);
      if (style.display === 'none' || style.visibility === 'hidden') return false;
    }
    return true;
  });
  const origin = dockOrigin;
  const target = origin?.path === route.fullPath
    ? targets.find(el => el === origin.element && el.dataset.focusId === origin.focusId)
    : undefined;
  if (target?.dataset.focusId) {
    const currentTargets = focusTargets(surface);
    const unchanged = origin!.scrollHeight === surface.scrollHeight && currentTargets.length === origin!.targets.length
      && currentTargets.every((el, index) => el === origin!.targets[index].element && el.dataset.focusId === origin!.targets[index].id);
    tvFocusRegistry.restore(target.dataset.focusId);
    if (unchanged) surface.scrollTop = origin!.scrollTop;
    return;
  }
  const recent = browseOrigin?.path === route.fullPath ? targets.find(el => el.dataset.focusId === browseOrigin!.focusId) : undefined;
  const area = surface.getBoundingClientRect();
  // If a pagination action vanished while the dock was focused, return near
  // the visible bottom rather than falling back to the page's first control.
  const nearby = recent ? undefined : targets.map(element => ({ element, rect: element.getBoundingClientRect() }))
    .filter(({ rect }) => rect.width > 0 && rect.height > 0)
    .sort((a, b) => {
      const visible = (rect: DOMRect) => rect.bottom > area.top && rect.top < area.bottom;
      return Number(visible(b.rect)) - Number(visible(a.rect)) || Math.abs(area.bottom - a.rect.bottom) - Math.abs(area.bottom - b.rect.bottom);
    })[0]?.element;
  const fallback = recent ?? nearby ?? targets[targets.length - 1];
  if (fallback?.dataset.focusId) tvFocusRegistry.restore(fallback.dataset.focusId);
}

function openPlayer(): void {
  void router.push({ name: 'now-playing' });
}

function onTopAccount(): void {
  window.dispatchEvent(new CustomEvent(accountLabel.value ? 'echo-tv-account-requested' : 'echo-tv-login-requested'));
}

function openExitDialog() {
  priorFocusId.value = (document.activeElement as HTMLElement | null)?.dataset.focusId ?? null;
  exitDialogOpen.value = true;
  releaseExitLayer = backLayers.register({ kind: 'dialog', close: closeExitDialog }, priorFocusId.value ?? undefined);
  void nextTick(() => {
    focusTargets(shell.value!.querySelector<HTMLElement>('[role="dialog"]')!).forEach((element) => tvFocusRegistry.register(element));
    tvFocusRegistry.restore('exit-cancel');
  });
}
function openLoginDialog() {
  loginSourceFocusId.value = (document.activeElement as HTMLElement | null)?.dataset.focusId ?? undefined;
  loginOpen.value = true;
}
function onLoginConfirmed() {
  window.dispatchEvent(new CustomEvent('echo-tv-auth-changed'));
}
async function onLogoutRequested() {
  await auth.logout();
  await app.loadSnapshot();
  window.dispatchEvent(new CustomEvent('echo-tv-auth-changed'));
}
function onAccountRequested() {
  if (route.name === 'profile') return;
  void router.push({ name: 'profile' });
}
function closeExitDialog() {
  exitDialogOpen.value = false;
  releaseExitLayer?.();
  releaseExitLayer = undefined;
}
function requestExit() {
  void nativeClient.request('app.exit', {}).catch(() => undefined);
  closeExitDialog();
}
function handleKey(event: KeyboardEvent) {
  // Any later user input supersedes an older async navigation focus transfer.
  navigationIntent++;
  pendingPlaybackReturn = undefined;
  const key = normalizeRemoteKey(event.key);
  pendingRouteReturn = undefined;
  const target = remoteEventTarget(event);
  if (key === 'ContextMenu' || key === 'Menu') {
    if (isTextEditingTarget(target) || playerActive.value || mvActive.value || backLayers.hasAny(blockingLayers)) return;
    event.preventDefault();
    if (!event.repeat) {
      if (target?.closest('[data-mini-player]')) returnFromDock();
      else focusPlaybackDock();
    }
    return;
  }
  if (PLAYER_MEDIA_KEYS.has(key)) {
    if (!event.repeat) {
      if (mvActive.value) {
        const method = key === 'MediaPlayPause' ? 'video.toggle' : 'video.seekBy';
        const payload = key === 'MediaTrackPrevious'
          ? { deltaMs: -10_000 }
          : key === 'MediaTrackNext'
            ? { deltaMs: 10_000 }
            : {};
        void nativeClient.request(method, payload).catch(() => undefined);
      } else if (key === 'MediaTrackPrevious') void player.previous().catch(() => undefined);
      else if (key === 'MediaTrackNext') void player.next().catch(() => undefined);
      else void player.toggle().catch(() => undefined);
    }
    event.preventDefault();
  } else if (key === 'Enter') {
    // Enter dispatched at the focused editor is handled by that editor so the
    // Android TV IME can submit instead of being converted to a generic click.
    if (isTextEditingTarget(target)) return;
    if (tvFocusRegistry.containModalFocus()) { event.preventDefault(); return; }
    if (!event.repeat && target?.dataset.focusId) target.click();
    event.preventDefault();
  } else if (key in REMOTE_DIRECTIONS) {
    // While the TV IME owns an input, D-pad belongs to the editor/IME. Back is
    // the explicit way to close that layer before spatial navigation resumes.
    if (isTextEditingTarget(target)) return;
    const direction = REMOTE_DIRECTIONS[key];
    if (!playerActive.value && !mvActive.value && !backLayers.hasAny(blockingLayers)) {
      if (target?.closest('[data-mini-player]') && direction === 'up') {
        event.preventDefault(); returnFromDock(); return;
      }
      if (contentSurface.value && target?.closest('.content-surface')) {
        event.preventDefault();
        if (!tvFocusRegistry.move(direction, contentSurface.value)) {
          if (direction === 'up') tvFocusRegistry.restore(primaryNavId.value);
          else if (direction === 'down') focusPlaybackDock();
        }
        return;
      }
      if (target?.closest('[data-top-nav]') && direction === 'down') {
        event.preventDefault();
        focusPageContent();
        return;
      }
    }
    if (tvFocusRegistry.move(direction)) event.preventDefault();
  } else if (key === 'GoBack') {
    event.preventDefault();
    if (!backLayers.hasAny(blockingLayers)) {
      if (target?.closest('[data-mini-player]')) { returnFromDock(); return; }
      if (target?.closest('[data-top-nav]')) { leaveCurrentPage(); return; }
    }
    lastBackLayer.value = backLayers.dispatch(fallbackLayers.value);
    backDispatchCount.value += 1;
  }
}
function keepFocusedTargetVisible(event: FocusEvent) {
  const target = event.target as HTMLElement | null;
  if (!target?.dataset.focusId) return;
  if (!target.closest('[data-mini-player]')) lastNonPlayerFocusId.value = target.dataset.focusId;
  const contentSurface = target.closest<HTMLElement>('.content-surface');
  if (!contentSurface) return;
  const page = target.closest<HTMLElement>('[data-page]');
  if (page && route.name !== 'now-playing') {
    dockOrigin = undefined;
    lastContentFocusId = target.dataset.focusId;
    browseOrigin = { path: route.fullPath, focusId: lastContentFocusId, scrollTop: contentSurface.scrollTop };
  }
  if (page && !target.closest('[role="dialog"]')) page.dataset.lastContentFocus = target.dataset.focusId;

  // The dock owns its grid row; the scroll surface never extends behind it.
  const miniPlayer = shell.value?.querySelector<HTMLElement>('[data-mini-player]');
  const targetRect = target.getBoundingClientRect();
  const miniRect = miniPlayer?.getBoundingClientRect();
  const horizontalOverlap = miniRect && targetRect.right > miniRect.left && targetRect.left < miniRect.right;
  // Reserve extra room for the 1.06 focused-card scale so the rendered card
  // still keeps at least a 24px visual gap above the MiniPlayer.
  scrollWithin(target, contentScrollOwner(target, contentSurface), {
    targetRect,
    bottomLimit: horizontalOverlap ? miniRect.top - 32 : undefined,
    behavior: 'auto',
    animate: target.closest<HTMLElement>('.tv-canvas')?.dataset.remoteNavigating === 'true',
  });
}

function hasRetainedFocus(element: HTMLElement | null): boolean {
  if (!element?.isConnected) return false;
  // The immersive player intentionally focuses its page instead of a control.
  // Lyrics/progress mutations must not replace that focus or its wake target.
  return Boolean(element.dataset.focusId) ||
    (route.name === 'now-playing' && element.matches('[data-page="now-playing"][data-player-idle="true"]')) ||
    (route.name === 'mv-detail' && element.matches('[data-page="mv-detail"][data-mv-idle="true"]'));
}

function scheduleDetachedFocusRepair() {
  if (restoreRouteOrigin()) return;
  const active = document.activeElement as HTMLElement | null;
  if (
    focusRepairScheduled ||
    exitDialogOpen.value ||
    loginOpen.value ||
    hasRetainedFocus(active)
  ) {
    return;
  }

  focusRepairScheduled = true;
  void nextTick(() => {
    focusRepairScheduled = false;
    if (restorePlaybackOrigin()) return;
    const settledActive = document.activeElement as HTMLElement | null;
    if (hasRetainedFocus(settledActive)) return;

    const targets = focusTargets(shell.value!);
    targets.forEach((element) => tvFocusRegistry.register(element));
    const availableIds = new Set(targets.map((element) => element.dataset.focusId));
    const requested = [
      lastNonPlayerFocusId.value,
      // A detail loading control may disappear before its async page resumes.
      // Keep repair inside that page instead of falling through to nav-home.
      shell.value?.querySelector<HTMLElement>('[data-page]')?.dataset.defaultFocusId,
      primaryNavId.value,
      'nav-home',
    ].find((id) => availableIds.has(id));
    if (requested) tvFocusRegistry.restore(requested);
  });
}

function focusTargets(node: Node): HTMLElement[] {
  if (!(node instanceof HTMLElement)) return [];
  return [
    ...(node.matches('[data-focus-id]') ? [node] : []),
    ...Array.from(node.querySelectorAll<HTMLElement>('[data-focus-id]')),
  ];
}

function restoreAfterRender(...ids: string[]) {
  void nextTick(() => {
    const targets = focusTargets(shell.value!);
    targets.forEach((element) => tvFocusRegistry.register(element));
    const availableIds = new Set(targets.map((element) => element.dataset.focusId));
    const requested = ids.find((id) => availableIds.has(id));
    if (requested) tvFocusRegistry.restore(requested);
    else tvFocusRegistry.restore(primaryNavId.value);
  });
}

function restoreNowPlayingFocus() {
  restoreAfterRender(player.currentTrack ? 'player-toggle' : 'player-empty-home', 'nav-home');
}

async function restoreStartupSession(): Promise<void> {
  try {
    // Encrypted credentials live in Android; never copy them into Vue storage.
    await auth.restore();
    if (sessionRestoreActive && !loginOpen.value) await app.loadSnapshot();
  } catch {
    // Keep anonymous navigation usable on a transport failure. The native
    // session manager retains credentials for transient account lookup errors.
  }
}

onMounted(() => {
  void restoreStartupSession();
  window.addEventListener('keydown', handleKey);
  window.addEventListener('echo-tv-login-requested', openLoginDialog);
  window.addEventListener('echo-tv-logout-requested', onLogoutRequested);
  window.addEventListener('echo-tv-account-requested', onAccountRequested);
  shell.value!.addEventListener('focusin', keepFocusedTargetVisible);
  focusTargets(shell.value!).forEach((element) => tvFocusRegistry.register(element));
  if (route.name === 'now-playing') restoreNowPlayingFocus();
  else tvFocusRegistry.restore(primaryNavId.value);
    focusObserver = new MutationObserver((records) => {
    records.forEach((record) => {
      record.removedNodes.forEach((node) => focusTargets(node).forEach((element) => tvFocusRegistry.unregister(element)));
      record.addedNodes.forEach((node) => focusTargets(node).forEach((element) => tvFocusRegistry.register(element)));
    });
    if (!restorePlaybackOrigin()) scheduleDetachedFocusRepair();
  });
  focusObserver.observe(shell.value!, { childList: true, subtree: true });
});
onBeforeUnmount(() => {
  cancelScrollMotion();
  releaseRouteGuard();
  releasePlayIntent();
  sessionRestoreActive = false;
  navigationIntent++;
  window.removeEventListener('keydown', handleKey);
  window.removeEventListener('echo-tv-login-requested', openLoginDialog);
  window.removeEventListener('echo-tv-logout-requested', onLogoutRequested);
  window.removeEventListener('echo-tv-account-requested', onAccountRequested);
  shell.value?.removeEventListener('focusin', keepFocusedTargetVisible);
  releaseExitLayer?.();
  focusObserver?.disconnect();
  focusTargets(shell.value!).forEach((element) => tvFocusRegistry.unregister(element));
  releasePlayerProjection();
  releaseAuthProjection();
});

watch(
  () => player.currentTrack,
  (current, previous) => {
    const active = document.activeElement as HTMLElement | null;
    if (route.name === 'now-playing' && Boolean(current) !== Boolean(previous)) {
      restoreNowPlayingFocus();
    } else if (previous && !current && active?.closest('[data-mini-player]')) {
      const fallback = lastNonPlayerFocusId.value;
      restoreAfterRender(fallback, `nav-${String(route.name ?? 'home')}`, 'nav-home');
    }
  },
);

watch(
  () => route.fullPath,
  (_current, previous) => {
    // RouterView pages share this scroll owner. A newly opened page must not
    // inherit the previous page's deep shelf/list position.
    if (contentSurface.value) contentSurface.value.scrollTop = 0;
    dockOrigin = undefined;
    void nextTick(() => { restoreRouteOrigin(); });
    const currentName = String(route.name ?? 'home');
    const previousName = previous ? String(router.resolve(previous).name ?? '') : '';
    if (currentName === 'now-playing') restoreNowPlayingFocus();
    else if (previousName === 'now-playing') {
      const returningOrigin = playbackOrigin?.path === route.fullPath ? playbackOrigin : undefined;
      pendingPlaybackReturn = returningOrigin;
      void nextTick(() => {
        // The MutationObserver may already have restored and consumed this origin.
        // Do not overwrite that successful restoration with the dock fallback.
        if (!restorePlaybackOrigin() && !returningOrigin) restoreAfterRender(player.currentTrack ? 'mini-open' : lastNonPlayerFocusId.value, `nav-${currentName}`, 'nav-home');
      });
    }
  },
  { flush: 'post' },
);
</script>

<template>
  <div
    ref="shell"
    class="tv-shell"
    :data-last-back-layer="lastBackLayer"
    :data-back-dispatch-count="backDispatchCount"
  >
    <div class="shell-background" :class="{ 'mv-active': mvActive, 'player-active': playerActive, 'has-dock': Boolean(player.currentTrack) && !playerActive && !mvActive }" v-bind="exitDialogOpen || loginOpen ? { inert: true } : {}">
      <TopNavigation v-if="!mvActive && !playerActive" :account-label="accountLabel" :has-track="Boolean(player.currentTrack)" @enter-content="enterNavigationContent" @open-player="openPlayer" @account="onTopAccount" />
      <div ref="contentSurface" class="content-surface" :style="{ '--tv-route-enter-x': `${routeDirection * 12}px` }"><RouterView /></div>
      <MiniPlayer v-if="player.currentTrack && route.name !== 'now-playing' && !mvActive" />
      <div v-if="player.commandFailure && !player.currentTrack && !mvActive" class="command-alert" role="alert">{{ player.commandFailure }}</div>
      <div v-else-if="player.error && !player.currentTrack && !playerActive && !mvActive" class="command-alert" role="alert">{{ player.error.message }}</div>
    </div>
    <TvOverlayTransition><div v-if="exitDialogOpen" class="dialog-backdrop">
      <section role="dialog" aria-modal="true" aria-labelledby="exit-title" class="exit-dialog">
        <span>ECHOMUSIC TV</span><h2 id="exit-title">退出 EchoMusic？</h2><p>音乐将停止播放，你可以随时再次打开。</p>
        <div><button v-tv-focus type="button" data-focus-id="exit-cancel" @click="closeExitDialog">继续聆听</button><button v-tv-focus type="button" data-focus-id="exit-confirm" @click="requestExit">退出</button></div>
      </section>
    </div></TvOverlayTransition>
    <LoginQrDialog
      :open="loginOpen"
      :source-focus-id="loginSourceFocusId"
      @close="loginOpen = false"
      @confirmed="onLoginConfirmed"
    />
  </div>
</template>

<style scoped>
.tv-shell { width:100%; height:100%; position:relative; }
.shell-background { width:100%; height:100%; position:relative; display:grid; grid-template-columns:minmax(0,1fr); grid-template-rows:88px minmax(0,1fr); gap:18px; }
.shell-background.has-dock { grid-template-rows:88px minmax(0,1fr) 112px; }
.shell-background.mv-active,.shell-background.player-active { grid-template-columns:minmax(0,1fr); grid-template-rows:minmax(0,1fr); gap:0; }
.shell-background.player-active { position:absolute; left:-96px; top:-54px; width:1920px; height:1080px; }
.shell-background.mv-active .content-surface,.shell-background.player-active .content-surface { overflow:hidden; border-radius:0; }
.content-surface { min-width:0; min-height:0; overflow-x:hidden; overflow-y:auto; border-radius:30px; scrollbar-width:none; scroll-behavior:smooth; }
.content-surface::-webkit-scrollbar { display:none; }
.command-alert { position:absolute; left:50%; bottom:134px; z-index:12; min-width:320px; padding:14px 22px; border:1px solid rgba(255,126,126,.35); border-radius:18px; transform:translateX(-50%); color:#fff; text-align:center; background:rgba(92,26,33,.92); box-shadow:0 18px 48px rgba(0,0,0,.38); }
.dialog-backdrop { position:absolute; inset:-54px -96px; z-index:20; display:grid; place-items:center; background:rgba(3,7,12,.82); }
.exit-dialog { width:560px; padding:42px; border:1px solid var(--tv-border); border-radius:30px; background:rgba(20,29,44,.94); box-shadow:0 35px 100px rgba(0,0,0,.5); }
.exit-dialog>span { color:#72b6ff; font-size:13px; letter-spacing:2px; font-weight:700; }.exit-dialog h2 { margin:10px 0 8px; font-size:38px; }.exit-dialog p { color:var(--tv-text-secondary); font-size:17px; }.exit-dialog div { margin-top:28px; display:flex; gap:14px; }.exit-dialog button { min-width:150px; height:52px; border:0; border-radius:26px; color:white; background:rgba(255,255,255,.1); }.exit-dialog button:first-child { background:var(--tv-blue); }
@media (prefers-reduced-motion:reduce) { .content-surface { scroll-behavior:auto; } }
</style>
