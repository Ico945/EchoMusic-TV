import type { RouteRecordRaw } from 'vue-router';
import LibraryPage from '../pages/LibraryPage.vue';
import FavoritesPage from '../pages/FavoritesPage.vue';
import HistoryPage from '../pages/HistoryPage.vue';
import CloudPage from '../pages/CloudPage.vue';
import PurchasedPage from '../pages/PurchasedPage.vue';
import ProfilePage from '../pages/ProfilePage.vue';
import ShareResolvePage from '../pages/ShareResolvePage.vue';

export const libraryRoutes: RouteRecordRaw[] = [
  { path: '/library', name: 'library', component: LibraryPage },
  { path: '/library/favorites', name: 'favorites', component: FavoritesPage },
  { path: '/library/history', name: 'history', component: HistoryPage },
  { path: '/library/cloud', name: 'cloud', component: CloudPage },
  { path: '/library/purchased', name: 'purchased', component: PurchasedPage },
  { path: '/profile', name: 'profile', component: ProfilePage },
  { path: '/share-resolve', name: 'share-resolve', component: ShareResolvePage },
];
