import { createPinia } from 'pinia';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { defineComponent, nextTick, ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { vTvFocus, tvFocusRegistry } from '../focus/directive';
import PaginationStatus from './PaginationStatus.vue';

enableAutoUnmount(afterEach);

function setup(initialItems = ['old-1', 'old-2']) {
  const items = ref(initialItems);
  const loading = ref(false), error = ref(false), hasMore = ref(true), context = ref('first');
  const load = vi.fn(() => { loading.value = true; error.value = false; });
  const wrapper = mount(defineComponent({
    components: { PaginationStatus },
    setup: () => ({ items, loading, error, hasMore, context, load }),
    template: `<div>
      <button v-tv-focus data-focus-id="mini-toggle">Player</button>
      <button v-tv-focus data-focus-id="nav-home">Home</button>
      <section data-page="test">
        <button v-for="(id, index) in items" :key="id" v-tv-focus :data-focus-id="id"
          :data-focus-up="items[index - 1]">{{ id }}</button>
        <PaginationStatus :loading="loading" :error="error" :has-more="hasMore"
          :item-focus-ids="items" :focus-context="context" @load-more="load" @retry="load" />
      </section>
    </div>`,
  }), { attachTo: document.body, global: { plugins: [createPinia()], directives: { tvFocus: vTvFocus } } });
  const action = () => wrapper.get<HTMLButtonElement>('.pagination-status button');
  const focus = (id: string) => wrapper.get<HTMLElement>(`[data-focus-id="${id}"]`).element.focus();
  const start = async () => { action().element.focus(); await action().trigger('click'); };
  return { wrapper, items, loading, error, hasMore, context, load, action, focus, start };
}

describe('PaginationStatus scoped focus handoff', () => {
  it.each([false, true])('adopts first-page loading focus without a click, but respects moving away (%s)', async movedAway => {
    const page = setup([]); page.loading.value = true; await nextTick();
    page.action().element.focus(); await nextTick();
    if (movedAway) page.focus('nav-home');
    page.items.value = ['first-new']; page.loading.value = false; await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe(movedAway ? 'nav-home' : 'first-new');
    expect(page.load).not.toHaveBeenCalled();
  });
  it.each([true, false])('focuses the first appended row with hasMore=%s before detached-focus repair can run', async hasMore => {
    const page = setup();
    await page.start();
    const source = page.action().element;
    const repair = vi.fn(() => {
      if (document.activeElement === document.body) page.focus('mini-toggle');
    });
    const observer = new MutationObserver(repair);
    observer.observe(page.wrapper.element, { subtree: true, childList: true });
    try {
      page.items.value.push('new-1', 'new-2');
      page.hasMore.value = hasMore;
      page.loading.value = false;
      await flushPromises();
      expect((document.activeElement as HTMLElement).dataset.focusId).toBe('new-1');
      expect(source.isConnected).toBe(hasMore);
      expect(tvFocusRegistry.move('up')).toBe(true);
      expect((document.activeElement as HTMLElement).dataset.focusId).toBe('old-2');
    } finally { observer.disconnect(); }
  });

  it('keeps failure and retry on the same enabled control and suppresses repeated confirms', async () => {
    const page = setup(); await page.start();
    const source = page.action().element;
    await page.action().trigger('click'); expect(page.load).toHaveBeenCalledOnce();
    expect(source.disabled).toBe(false); expect(source.getAttribute('aria-busy')).toBe('true');
    page.error.value = true; page.loading.value = false; await nextTick();
    expect(document.activeElement).toBe(source);
    expect(source.dataset.focusId).toBe('pagination-retry');
    expect(tvFocusRegistry.move('up')).toBe(true);
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('old-2');
    await page.start(); expect(page.action().element).toBe(source);
    page.items.value.push('retried'); page.loading.value = false; page.hasMore.value = false;
    await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe('retried');
  });

  it.each(['mini-toggle', 'nav-home', 'old-1'])('preserves a move to %s during loading', async destination => {
    const page = setup(); await page.start(); page.focus(destination);
    page.items.value.push('new-1'); page.hasMore.value = false; page.loading.value = false;
    await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe(destination);
  });

  it('does not reclaim focus after the viewer moves away and back during loading', async () => {
    const page = setup(); await page.start(); page.focus('nav-home'); page.action().element.focus();
    page.items.value.push('new-1'); page.loading.value = false; await flushPromises();
    expect(document.activeElement).toBe(page.action().element);
  });

  it.each([true, false])('handles an empty or duplicate-only next page with hasMore=%s near the old bottom', async hasMore => {
    const page = setup(); await page.start();
    page.items.value = [...page.items.value]; page.loading.value = false; page.hasMore.value = hasMore;
    await flushPromises();
    expect((document.activeElement as HTMLElement).dataset.focusId).toBe(hasMore ? 'pagination-load-more' : 'old-2');
  });

  it('keeps a completion control when an empty terminal response has no local fallback', async () => {
    const page = setup([]); await page.start();
    page.loading.value = false; page.hasMore.value = false; await flushPromises();
    expect(document.activeElement).toBe(page.action().element);
    expect(page.action().text()).toBe('已全部加载');
    await page.action().trigger('click'); expect(page.load).toHaveBeenCalledOnce();
    page.focus('nav-home'); await nextTick();
    expect(page.wrapper.find('.pagination-status button').exists()).toBe(false);
  });

  it('invalidates a pending handoff when the query, tab or resource changes', async () => {
    const page = setup(); await page.start();
    page.context.value = 'second'; page.items.value = ['different']; page.loading.value = false;
    await flushPromises(); expect(document.activeElement).toBe(page.action().element);
  });

  it('does not focus behind a newly mounted dialog or after unmount', async () => {
    const page = setup(); await page.start();
    const modal = document.createElement('div'); modal.setAttribute('aria-modal', 'true');
    document.body.append(modal);
    try {
      page.items.value.push('new-1'); page.loading.value = false; await flushPromises();
      expect(document.activeElement).toBe(page.action().element);
      page.wrapper.unmount(); page.items.value.push('late'); await flushPromises();
      expect(document.activeElement).toBe(document.body);
    } finally { modal.remove(); }
  });
});
