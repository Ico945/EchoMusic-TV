import type { RouteRecordRaw } from 'vue-router';
import ExplorePage from '../pages/ExplorePage.vue';
import DailyRecommendationsPage from '../pages/DailyRecommendationsPage.vue';
import PrivateFmPage from '../pages/PrivateFmPage.vue';
import RankingPage from '../pages/RankingPage.vue';
import SearchPage from '../pages/SearchPage.vue';
import PlaylistDetailPage from '../pages/PlaylistDetailPage.vue';
import AlbumDetailPage from '../pages/AlbumDetailPage.vue';
import ArtistDetailPage from '../pages/ArtistDetailPage.vue';
import SongDetailPage from '../pages/SongDetailPage.vue';

export const discoveryRoutes: RouteRecordRaw[] = [
  { path: '/explore', name: 'explore', component: ExplorePage },
  { path: '/daily-recommendations', name: 'daily-recommendations', component: DailyRecommendationsPage },
  { path: '/fm', name: 'fm', component: PrivateFmPage },
  { path: '/ranking', name: 'ranking', component: RankingPage },
  { path: '/ranking/:id', name: 'ranking-detail', component: RankingPage },
  { path: '/search', name: 'search', component: SearchPage },
  { path: '/playlist/:id', name: 'playlist-detail', component: PlaylistDetailPage },
  { path: '/album/:id', name: 'album-detail', component: AlbumDetailPage },
  { path: '/artist/:id', name: 'artist-detail', component: ArtistDetailPage },
  { path: '/song/:id', name: 'song-detail', component: SongDetailPage },
];
