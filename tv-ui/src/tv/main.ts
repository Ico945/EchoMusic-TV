import { createPinia } from 'pinia';
import { createApp } from 'vue';
import './styles/tokens.css';
import './styles/global.css';
import TvApp from './TvApp.vue';
import { vTvFocus } from './focus/directive';
import { createTvAppRouter } from './router';

const app = createApp(TvApp);

app.use(createPinia());
app.use(createTvAppRouter());
app.directive('tv-focus', vTvFocus);
app.mount('#app');
