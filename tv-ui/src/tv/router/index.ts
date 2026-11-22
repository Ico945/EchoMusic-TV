import { createMemoryHistory, createRouter, createWebHashHistory, type Router, type RouterHistory } from 'vue-router';
import HomePage from '../pages/HomePage.vue';
import MvDetailPage from '../pages/MvDetailPage.vue';
import NowPlayingPage from '../pages/NowPlayingPage.vue';
import { discoveryRoutes } from './discoveryRoutes';
import { libraryRoutes } from './libraryRoutes';
import { moreRoutes } from './moreRoutes';
import { navigationSection, TOP_NAV_ORDER } from './navigation';

export const TV_NAV_ITEMS = [
  { id: 'home', label: '首页', icon: 'home' },
  { id: 'explore', label: '发现', icon: 'discover' },
  { id: 'ranking', label: '排行榜', icon: 'chart' },
  { id: 'library', label: '资料库', icon: 'library' },
  { id: 'search', label: '搜索', icon: 'search' },
  { id: 'more', label: '更多', icon: 'more' },
] as const;

export function createTvRouter(history: RouterHistory = createMemoryHistory('/')): Router {
  const router = createRouter({
    history,
    routes: [
      { path: '/', redirect: '/home' },
      { path: '/home', name: 'home', component: HomePage },
      { path: '/mv/:id', name: 'mv-detail', component: MvDetailPage },
      { path: '/now-playing', name: 'now-playing', component: NowPlayingPage },
      ...discoveryRoutes,
      ...libraryRoutes,
      ...moreRoutes,
    ],
  });
  router.beforeEach((to, from) => {
    if (!TOP_NAV_ORDER.some(id => id === to.name) && !to.query.sourceSection && from.name) {
      // A pop/hash navigation has already written the destination into history.
      // Enrich that entry in place, or Back would land on the unannotated URL
      // and redirect into the same detail forever. Ordinary push must keep its
      // parent entry so returning from artist/song/player still works.
      return { path: to.path, query: { ...to.query, sourceSection: navigationSection(from) }, hash: to.hash,
        replace: history.location === to.fullPath };
    }
  });
  return router;
}

export function createTvAppRouter(): Router {
  return createTvRouter(createWebHashHistory());
}
