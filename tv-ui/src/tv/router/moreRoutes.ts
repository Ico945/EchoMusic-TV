import type { RouteRecordRaw } from 'vue-router';
import SettingsPage from '../pages/SettingsPage.vue';
import RecognizePage from '../pages/RecognizePage.vue';
import MorePage from '../pages/MorePage.vue';
import PluginPage from '../pages/PluginPage.vue';
import AboutPage from '../pages/AboutPage.vue';

export const moreRoutes: RouteRecordRaw[] = [
  { path: '/more', name: 'more', component: MorePage },
  { path: '/settings', name: 'settings', component: SettingsPage },
  { path: '/recognize', name: 'recognize', component: RecognizePage },
  { path: '/plugin', name: 'plugin', component: PluginPage },
  { path: '/about', name: 'about', component: AboutPage },
];
