import { createPinia, setActivePinia } from 'pinia';
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createTvRouter } from '../router';
import { vTvFocus } from '../focus/directive';
import { BACK_LAYER_REGISTRY_KEY, BackLayerRegistry } from '../input/backCoordinator';
import { installNativeClientForTest } from '../native/client';
import { useTvPlayerStore } from '../stores/player';
import { useTvSearchStore } from '../stores/search';
import { useTvBrowseStore } from '../stores/browse';
import PlayerControls from '../player/PlayerControls.vue';
import QueueDrawer from '../player/QueueDrawer.vue';
import SearchPage from './SearchPage.vue';
import ExplorePage from './ExplorePage.vue';
import NowPlayingPage from './NowPlayingPage.vue';

enableAutoUnmount(afterEach);
const request = vi.fn();
const tracks = Array.from({length:4}, (_, i) => ({ id:`r53-${i}`,title:`Song ${i}`,artists:['Artist'],album:'',durationMs:180000,qualities:['128'] }));
beforeEach(() => {
  setActivePinia(createPinia()); localStorage.clear();
  request.mockReset().mockResolvedValue({accepted:true});
  installNativeClientForTest({request, on:()=>()=>{}});
  window.EchoTvNative = {postMessage(){},onmessage:null};
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => new DOMRect(10,10,100,60));
});
afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); delete window.EchoTvNative; });
async function setup(path = '/now-playing') {
  const router = createTvRouter(); await router.push(path); await router.isReady();
  const player = useTvPlayerStore();
  player.applySnapshot({currentTrack:tracks[0],queue:tracks,queueIndex:0,isPlaying:false,positionMs:30000,durationMs:180000,quality:'128',repeatMode:'off',lyrics:[],error:null,errorRevision:0});
  return {player, router, global:{plugins:[router],directives:{tvFocus:vTvFocus},provide:{[BACK_LAYER_REGISTRY_KEY as symbol]:new BackLayerRegistry(()=>{})}}};
}
const focus = (id:string) => document.querySelector<HTMLElement>(`[data-focus-id="${id}"]`)!.focus();

describe('r53 TV interaction refinement', () => {
  it('cancels seek with Back without sending a command or bubbling to route Back', async () => {
    const {global} = await setup(); const w=mount(PlayerControls,{global,attachTo:document.body});
    const bar=w.get('[data-focus-id=player-progress]');
    await bar.trigger('keydown',{key:'ArrowRight'});
    const back = new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true});
    bar.element.dispatchEvent(back); await flushPromises();
    expect(back.defaultPrevented).toBe(true); expect(bar.attributes('data-seek-preview')).toBe('false'); expect(request).not.toHaveBeenCalled();
  });
  it('clamps preview at both bounds, never modifies authoritative playback position', async () => {
    const {global,player}=await setup(); const w=mount(PlayerControls,{global});
    const bar=w.get('[data-focus-id=player-progress]');
    for(let i=0;i<30;i++) await bar.trigger('keydown',{key:'ArrowRight',repeat:true});
    expect(bar.text()).toContain('3:00'); expect(player.positionMs).toBe(30000);
    for(let i=0;i<30;i++) await bar.trigger('keydown',{key:'ArrowLeft',repeat:true});
    expect(bar.text()).toContain('0:00'); await bar.trigger('click');
    expect(request).toHaveBeenCalledWith('player.seekTo',{trackId:'r53-0',positionMs:0});
  });
  it('cancels a preview on focus departure or song change', async () => {
    const {global,player}=await setup(); const w=mount(PlayerControls,{global}); const bar=w.get('[data-focus-id=player-progress]');
    await bar.trigger('keydown',{key:'ArrowRight'}); await bar.trigger('blur'); await bar.trigger('click'); expect(request).not.toHaveBeenCalled();
    await bar.trigger('keydown',{key:'ArrowRight'}); player.currentTrack=tracks[1]; await flushPromises();
    await bar.trigger('click'); expect(request).not.toHaveBeenCalled();
  });
  it('guards repeated commit while pending and keeps failed preview retryable', async () => {
    const {global}=await setup(); let reject!:(reason:Error)=>void;
    request.mockImplementationOnce(()=>new Promise((_r,j)=>{reject=j;}));
    const w=mount(PlayerControls,{global}); const bar=w.get('[data-focus-id=player-progress]');
    await bar.trigger('keydown',{key:'ArrowRight'}); await bar.trigger('click'); await bar.trigger('click');
    expect(request).toHaveBeenCalledTimes(1); reject(new Error('offline')); await flushPromises();
    expect(w.text()).toContain('跳转失败'); expect(bar.attributes('data-seek-preview')).toBe('true');
    await bar.trigger('click'); expect(request).toHaveBeenCalledTimes(2);
  });
  it('keeps idle chrome visible during a seek preview', async () => {
    vi.useFakeTimers(); const {global,player}=await setup(); player.isPlaying=true;
    const w=mount(NowPlayingPage,{global,attachTo:document.body});
    await w.get('[data-focus-id=player-progress]').trigger('keydown',{key:'ArrowRight'});
    await vi.advanceTimersByTimeAsync(12000);
    expect(w.attributes('data-player-idle')).toBe('false');
  });
  it('disables meaningless queue edits and guards selection during editing', async () => {
    const {global}=await setup(); const w=mount(QueueDrawer,{global,props:{queue:[tracks[0]],queueIndex:0,selecting:true}});
    for(const id of ['queue-clear','queue-shuffle','queue-next','queue-remove']) expect(w.get(`[data-focus-id=${id}]`).attributes('aria-disabled')).toBe('true');
    await w.get('[data-focus-id=queue-track-r53-0]').trigger('click');
    await w.get('[data-focus-id=queue-clear]').trigger('click'); expect(w.emitted('select')).toBeUndefined(); expect(request).not.toHaveBeenCalled();
  });
  it('repairs a removed selection and can jump to the current track', async () => {
    const {global}=await setup(); const w=mount(QueueDrawer,{global,attachTo:document.body,props:{queue:tracks,queueIndex:0}});
    focus('queue-track-r53-2'); await flushPromises();
    await w.setProps({queue:[tracks[0],tracks[1]]}); await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('queue-track-r53-0');
    focus('queue-track-r53-1'); await w.get('[data-focus-id=queue-current]').trigger('click'); await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('queue-track-r53-0');
  });
  it('does not cancel clear confirmation on routine native snapshot object refreshes', async () => {
    const {global}=await setup(); const w=mount(QueueDrawer,{global,props:{queue:tracks,queueIndex:0}});
    await w.get('[data-focus-id=queue-clear]').trigger('click');
    await w.setProps({queue:tracks.map(t=>({...t}))}); await flushPromises();
    expect(w.get('[data-focus-id=queue-clear]').text()).toContain('确认清空');
    await w.get('[data-focus-id=queue-clear]').trigger('click');
    expect(request).toHaveBeenCalledWith('player.editQueue',{action:'clearPending'});
  });
  it('opens the keyboard for an empty search submission', async () => {
    const {global}=await setup('/search'); const search=useTvSearchStore(); search.hotTerms=['Music'];
    const w=mount(SearchPage,{global,attachTo:document.body}); await flushPromises();
    await w.get('[data-focus-id=search-submit]').trigger('click'); await flushPromises();
    expect(request).toHaveBeenCalledWith('app.showIme',{}); expect(search.submitted).toBe(false);
  });
  it('requires a second confirmation before clearing search history', async () => {
    const {global}=await setup('/search'); const search=useTvSearchStore(); search.hotTerms=['Music']; search.history=['History'];
    const w=mount(SearchPage,{global,attachTo:document.body}); await flushPromises();
    const clear=w.get('[data-focus-id=search-history-clear]');
    await clear.trigger('click'); expect(search.history).toEqual(['History']);
    await clear.trigger('blur'); await clear.trigger('click'); expect(search.history).toEqual(['History']);
    await clear.trigger('click'); expect(search.history).toEqual([]);
  });
  it('retains retry focus while hot search loads then enters recovered terms', async () => {
    const {global}=await setup('/search'); request.mockRejectedValueOnce(new Error('offline'));
    const w=mount(SearchPage,{global,attachTo:document.body}); await flushPromises();
    expect(w.text()).toContain('热搜暂时不可用'); focus('search-hot-retry');
    let resolve!:(value:unknown)=>void; request.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
    await w.get('[data-focus-id=search-hot-retry]').trigger('click');
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('search-hot-retry');
    resolve({terms:['Recovered']}); await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('search-hot-0');
  });
  it('limits featured rankings without deleting full category content', async () => {
    const {global,router}=await setup('/explore'); const browse=useTvBrowseStore(); browse.loaded=true;
    browse.shelves=[{id:'rankings',title:'排行榜',status:'ready',cards:[],tracks:[],rankings:Array.from({length:56},(_,i)=>({id:`rank-${i}`,title:`Rank ${i}`,artwork:''}))}];
    const w=mount(ExplorePage,{global,attachTo:document.body}); await flushPromises();
    expect(w.findAll('.ranking-card')).toHaveLength(8);
    expect(w.get('[data-focus-id=explore-rankings-rank-7]').attributes('data-focus-down')).toBe('explore-rankings-more');
    focus('explore-rankings-more');
    await w.get('[data-focus-id=explore-rankings-more]').trigger('click'); await flushPromises();
    expect(router.currentRoute.value.query.category).toBe('rankings'); expect(w.findAll('.ranking-card')).toHaveLength(56);
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('explore-rankings-rank-0');
  });
});
